# 临时环境生命周期管控（ephemeral-env-control-java）

在 Spring Boot 骨架上实现的一套**纯本地**临时环境生命周期管控服务：用进程内状态模拟环境的
申请（apply）、持有（active）、过期（expired）与回收（recycled），保证并发申请、重复请求与
异常回收下结论一致、可解释、可重试。不依赖任何外部服务或真实凭据。

- JDK 21（可在 JDK 25 上以 `--release 21` 编译）、Spring Boot 3.4.1、JUnit 5
- 状态、配额、幂等记录全部保存在内存中；回收动作用 `SimulatedRecycler` 本地模拟，可注入失败
- 时间用可替换的 `Clock` 抽象，测试用 `MutableClock` 手动推进，无需真实等待

## 1. 租约状态机

每个环境（`envId`）对应一条租约 `Lease`，状态共 4 个：

```
                         申请成功
   (不存在 / RELEASED) ───────────────▶ ACTIVE
                                          │
              持有者主动释放(未到期) ───────┤
              到期 now>=expireAt           │
              (惰性判定 / sweep 巡检)      ▼
                                       EXPIRED   （配额仍占用，等待回收）
                                          │
                                发起回收   │
                                          ▼
                ┌──────────────────▶ RELEASING  ◀──────────────┐
                │                    │      │                    │
                │   回收外部动作失败  │      │ 回收外部动作失败    │
                └────────────────────┘      └────────────────────┘
                                     │
                              回收成功（代际 +1，清空持有者）
                                     ▼
                                  RELEASED （终态，配额已归还，可换代重开）
```

关键规则：

1. **配额只被 ACTIVE / EXPIRED / RELEASING 占用**；到期不归还配额，只有“回收成功”才归还。
   这样过期与回收失败都不会造成配额泄漏或被超额申请钻空。
2. **回收失败收敛为 RELEASING**：状态确定、配额继续占用；允许用**新的幂等键重试**
   （`retry-recycle` / `expire-recycle`）。同一幂等键重复提交永远返回首次冻结结论。
3. **代际 generation 防迟到回收**：每次回收成功 generation +1。回收的外部动作在状态锁外执行，
   回调收尾时若发现代际或状态已变化（环境已被换代重开），旧结果判定为
   `LATE_RECYCLE_IGNORED` 并丢弃，当前状态绝不回退。
4. **并发回收**：一次回收在锁外执行期间，该 envId 记为“在途”；其它并发请求收到
   `ALREADY_RELEASING`（冲突、可重试），且回收器不会被第二次调用。
5. 只有 `RELEASED` 允许同一 envId 重新申请（换代重开）；ACTIVE/EXPIRED/RELEASING 期间
   不同持有者争用一律拒绝，互不覆盖。

## 2. 幂等与争用

- 每次写操作必须携带 `idempotencyKey` 与持有者 `holder`（系统回收接口 holder 为空身份）。
- 幂等指纹 = `(idempotencyKey, operation, holder, envId)`：
  - 指纹一致的重复提交：直接返回**首次冻结结论**（含首次决策码、到期时间、代际快照），不再次生效；
  - 同一 key 指纹不一致：返回 HTTP 409 / `IDEMPOTENT_KEY_CONFLICT`，不覆盖首次结论。
- 争用同一环境：
  - 本人再次申请已持有环境 → `ENV_OCCUPIED_BY_SELF`
  - 其他持有者申请 → `ENV_OCCUPIED_BY_OTHER`
  - 非持有者主动释放 → `NOT_LEASE_OWNER`

## 3. 两级配额规则

默认 **每个持有者上限 3、全局限额 5、默认租约 TTL 60s**（`EnvLeaseManager` 构造器可覆盖；
单测中按用例自定义）。

- 申请时**先校验持有者维度，再校验全局维度**，因此超额原因可区分：
  - `HOLDER_QUOTA_EXCEEDED`：本人已达上限（此时别人仍可能申请成功）
  - `GLOBAL_QUOTA_EXCEEDED`：全局已满
- 扣减只发生在“回收成功”这一个收敛点，且在同一把状态锁内与状态迁移一起完成，杜绝重复扣减。
- `GET /api/envs/quota` 返回持有者明细、全局用量与占用租约数。
- `EnvLeaseManager.auditConservation()` 做守恒审计：
  `Σ 各持有者占用 == globalUsed == 占用态租约数`，任一不符即失败。所有并发/异常用例前后都断言守恒。

## 4. 决策码（结论可区分、可重试）

| 决策码 | 成功 | 可重试 | 冲突 | 含义 |
| --- | --- | --- | --- | --- |
| APPLY_SUCCESS | 是 | 否 | 否 | 申请生效 |
| HOLDER_QUOTA_EXCEEDED | 否 | 否 | 否 | 持有者配额满 |
| GLOBAL_QUOTA_EXCEEDED | 否 | 否 | 否 | 全局配额满 |
| ENV_OCCUPIED_BY_SELF | 否 | 否 | 是 | 本人已持有 |
| ENV_OCCUPIED_BY_OTHER | 否 | 否 | 是 | 他人持有/回收中 |
| IDEMPOTENT_KEY_CONFLICT | 否 | 否 | 是 | 幂等键指纹冲突（HTTP 409） |
| LEASE_NOT_FOUND | 否 | 否 | 否 | 租约不存在 |
| NOT_LEASE_OWNER | 否 | 否 | 是 | 非持有者释放 |
| RELEASE_SUCCESS / RECYCLE_SUCCESS | 是 | 否 | 否 | 回收完成、配额归还 |
| ALREADY_RELEASING | 否 | 是 | 是 | 回收在途，并发请求被拒 |
| ALREADY_RELEASED | 否 | 否 | 是 | 重复/迟到回收，状态不回退 |
| RECYCLE_FAILED | 否 | 是 | 否 | 回收中途失败，已收敛 RELEASING |
| LATE_RECYCLE_IGNORED | 否 | 否 | 是 | 旧代际迟到回调被忽略 |

“失败”（retryable=true，如 RECYCLE_FAILED）与“冲突”（conflict=true，如 ALREADY_RELEASING /
ALREADY_RELEASED）明确区分：前者应重试同一环境，后者说明请求与当前状态不符。

## 5. HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/envs/apply` | 申请；body: idempotencyKey, holder, envId, ttlMillis(可选) |
| POST | `/api/envs/release` | 持有者主动释放；body: idempotencyKey, holder, envId |
| POST | `/api/envs/expire-recycle` | 系统对过期租约发起回收；body: idempotencyKey, envId |
| POST | `/api/envs/retry-recycle` | RELEASING 失败后重试；body: idempotencyKey, envId |
| POST | `/api/envs/sweep` | 巡检：ACTIVE 到期者批量置 EXPIRED |
| GET  | `/api/envs/{envId}` | 查询租约快照 |
| GET  | `/api/envs/quota` | 配额与租约计数快照 |

响应统一含 `decision / success / retryable / conflict / reason / basis / generation`，
其中 `basis` 给出本次判定依据。参数错误返回 400，幂等指纹冲突返回 409。

快速本地运行（可选）：

```bash
mvn -o spring-boot:run
curl -s -XPOST localhost:8080/api/envs/apply -H 'Content-Type: application/json' \
  -d '{"idempotencyKey":"k1","holder":"alice","envId":"env-demo","ttlMillis":60000}'
curl -s localhost:8080/api/envs/quota
```

## 6. 验证方法

全部验证只依赖本地 Maven 与已有依赖，离线可跑：

```bash
mvn -o test            # 全量单测（25 个用例 + Spring 上下文 / REST 冒烟）
```

测试覆盖（`src/test/java/.../tests`）：

- `LifecycleIdempotencyTest`：申请/释放主路径、同键多次重放结论一致、同键异指纹冲突、
  异主争用不覆盖、本人重复申请不重复计费、RELEASED 换代重开。
- `QuotaTest`：持有者/全局超额可区分、释放归还配额、回收失败保配额且重试只扣减一次、
  过期仍占配额直到回收。
- `RecycleFailureTest`：到期巡检与惰性到期、同键重复回收重放、成功后重复回收不回退、
  **旧代际迟到回调被忽略**、失败→再失败→恢复重试收敛、越权释放、未知租约。
- `ConcurrentLifecycleTest`：32 线程同键并发申请只生效一次、16 持有者争用只有一个赢家、
  回收在途时并发重复回收（回收器仅调用一次、配额只扣一次）、40 线程争用全局配额绝不超限。
- `EnvControllerWebTest`：REST 申请/重放/409/释放/400 端到端。
- `ApplicationTests`：Spring 上下文本地可装配。

单测日志按要求打印 **幂等标识（idempotencyKey）、操作类型（op）、决策码（decision）
与判定依据（basis）**，例如：

```
idempotencyKey=frel1 op=RELEASE env=env-fail holder=alice decision=RECYCLE_FAILED
  success=false retryable=true conflict=false basis=... 保持RELEASING 配额继续占用，可重试
```

## 7. 主要代码结构

```
model/      LeaseStatus / OperationType / DecisionCode / Lease / LeaseResult / IdempotentRecord
support/    Clock、SystemClock、MutableClock(测试)、QuotaSnapshot
recycler/   EnvRecycler 接口、SimulatedRecycler（可注入失败）
service/    EnvLeaseManager（状态机+配额+幂等+并发控制）、LocalConfig、IdempotentConflictException
web/        EnvController、请求 DTO、ApiExceptionHandler
```

并发模型：单把 `stateLock` 保护所有状态迁移与配额计数；回收器 IO 在锁外执行，
靠 `inFlightEnvIds` 区分“在途并发回收”与“失败后重试”，靠 generation 拒绝迟到旧回调。
