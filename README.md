# 临时环境生命周期管控（本地状态模拟）

在 Spring Boot 骨架之上实现的一套临时环境申请 / 续期 / 持有 / 过期 / 回收管控流程。
全部状态保存在进程内（`ConcurrentHashMap`），回收动作由可注入的本地模拟器
`CountingFailureSimulator` 执行，**不依赖任何外部服务或真实凭据**。

## 1. 租约状态机

```
                         acquire 成功（配额通过 + 环境空闲/终态）
        (不存在 / RELEASED / RECLAIMED) ───────────────────────► ACTIVE
                                                                   │  │
                                                                   │  └── renew（持有者，未到期）：到期时刻 T → T+Δ，状态不变（可多次累加）
                        release（持有者，未到期）                   │ TTL 到期
                 ┌────────────────────────────────────► RELEASING ─┤ reclaim 时懒标记
                 │ （本地动作立即完成）                               │
                 ▼                                                   ▼
             RELEASED ◄──终态，配额释放                          EXPIRED
                                                                   │ reclaim（过期 / 异常）
                                                                   ▼
                                                               RECLAIMING
                                                                   │ 回收动作成功
                                                                   ▼
                                                               RECLAIMED ◄──终态，配额释放
                                                                   │
                                                   回收动作失败：停留在 RECLAIMING（可重试）
```

关键规则：

- `ACTIVE`：持有中。未到期且持有者匹配才能 `release` / `renew`；TTL 到期后只能 `reclaim`。
- `EXPIRED`：检测态。到期在 `reclaim/sweep` 时通过 CAS 懒标记，不做后台线程。
- `RELEASING / RECLAIMING`：中间态，**仍然占用配额**（资源尚未真正归还）。
  - `RECLAIMING` 表示回收动作中途失败，**不回退**到 EXPIRED，允许且应当重试。
- `RELEASED / RECLAIMED`：终态。重复释放 / 重复回收 / 迟到回收直接返回首次结论
  （`replayed=true`），状态绝不回退；终态环境可以被重新申请。

状态流转全部通过存储层单 key 原子 `compute`（CAS 断言）完成；释放/回收/续期另加
**每环境互斥锁**，保证重复、迟到、并发回收 / 续期只会有一条链路真正改变状态；
申请走**全局申请锁**，使“配额判定 + 环境争用判定 + 建租约 + 写幂等结论”成为
一个临界区，杜绝并发超发与相互覆盖。

> 续期是 ACTIVE 上的“原地延期”，不产生新状态、不改变租约占用身份：
> `ACTIVE(到期时刻 T) ──renew(Δ)──► ACTIVE(到期时刻 T+Δ)`。

### 1.1 续期规则

- 仅 **持有者本人**、租约处于 **ACTIVE 且当前时刻尚未到到期临界点** 时可以续期成功。
- 新到期时刻 = **原到期时刻 + 顺延时长**。多次续期按上一次到期时刻累加顺延；
  已经流逝的时间不会被“补回来”（不是从当前时刻重新起算）。
- 续期只改 `expireAtMillis`，**不改 state、不改 owner、不新增租约**，因此不改变任何
  占用数量，不可能多占一份配额；配额实时统计算法天然把续期排除在计数增减之外。
- **不能救活**：
  - 已越过到期临界点（即使仍是懒标记前的 ACTIVE）→ `ALREADY_EXPIRED`；
  - 已进入 `RELEASING / RECLAIMING`（含回收中途失败等待重试）→
    `RELEASING / RECLAIMING`，续期明确判迟到；
  - 已处于 `RELEASED / RECLAIMED` 终态 → `TERMINAL`。
  - 任何迟到续期都不会把环境从回收 / 终态拉回 ACTIVE。
- 续期成功后环境继续归该持有者使用：旧 TTL 到期点上的回收 / 扫描会因
  “尚未到期”被挡下，他人申请依旧得到 `ENV_HELD_BY_ANOTHER_OWNER`。

## 2. 幂等规则

- 申请必须携带 `idempotencyId` 与 `owner`。
- 首次结论（**成功或拒绝**）永久记录；相同幂等标识重复提交：
  - 返回与首次一致的结果（同一份租约 / 同一个拒绝原因），`replayed=true`；
  - 不重复占用或释放配额；
  - 若同一幂等标识绑定的 `owner/envName` 与首次不同，返回可重试的 `conflict`。
- **续期同样必须携带 `idempotencyId` 与 `owner`**，使用独立于申请的续期幂等记录：
  - 首次续期结论（成功后的租约快照 / 首次拒绝原因）永久记录；
  - 相同标识重复提交只顺延一次，后续全部返回**首次续期后的同一份租约快照**
    （到期时刻、持有者、状态完全一致），`replayed=true`，不会重复累加时长；
  - 首次被拒（越权 / 已过期 / 回收中 / 终态）的续期，重复提交一致重放同一个拒绝原因；
  - 标识绑定的 `owner/envName` 与首次不同时返回可重试的 `conflict`，调用方应换键。
- 释放与回收天然幂等：终态上重复调用返回首次结论，不做第二次扣减。

## 3. 两级配额规则

| 维度 | 默认上限 | 拒绝原因（`RejectionReason`） |
|---|---|---|
| 持有者 | 2（`ephemeral.quota.per-owner`） | `OWNER_QUOTA_EXCEEDED` |
| 全局 | 4（`ephemeral.quota.global`） | `GLOBAL_QUOTA_EXCEEDED` |

- 判定顺序：环境争用 → 持有者配额 → 全局配额（先报更具体的维度）。
- 环境被其他持有者占用时拒绝原因：`ENV_HELD_BY_ANOTHER_OWNER`。
- **防泄漏 / 防重复扣减的实现方式**：不维护独立计数器，而是在每次判定时从
  租约存储实时统计处于占用态（`ACTIVE/EXPIRED/RELEASING/RECLAIMING`）的租约。
  一条租约在生命周期内只会在“占用 / 不占用”之间切换一次（进入终态），
  因此释放、过期、回收失败都不可能泄漏或重复扣减配额。
- 回收失败停留在 `RECLAIMING`，配额**暂不释放**；重试回收成功进入 `RECLAIMED`
  后才释放——避免“配额已释放但资源没回收”的泄漏。

## 4. 失败与冲突的区分（可解释、可重试）

| 场景 | 响应 / 异常 | retryable | 说明 |
|---|---|---|---|
| 配额超额、环境被他人占用 | `AcquireRejectedException`，HTTP 409 `type=rejected` | false | 确定性结论，不应原样重试 |
| 幂等键参数不一致、未到期回收、中间态冲突、非持有者释放 | `ConflictException`，HTTP 409 `type=conflict` | true | 状态冲突，修正或稍后重试 |
| 续期被拒：非持有者 / 已过期 / 回收中 / 终态 / 无租约 | `RenewalRejectedException`，HTTP 409 `type=renewal_rejected` | 因原因而异（见下） | 携带可区分 `reason`，绝不会“假成功” |
| 回收中途失败 | `ReclaimFailureException`，HTTP 202 `type=reclaim_failed` | true | 停留 RECLAIMING，重试回收即可收敛 |

续期拒绝原因（`RenewalRejectedReason`）与重试语义：

| reason | retryable | 含义 |
|---|---|---|
| `NOT_OWNER` | false | 调用方不是持有者，换身份 / 环境无意义 |
| `LEASE_NOT_FOUND` | false | 环境不存在任何租约 |
| `ALREADY_EXPIRED` | false | 已越过到期临界点（含 EXPIRED），续期迟到、禁止救活 |
| `RELEASING` | true | 环境正在主动释放，释放完成后状态会变，可稍后重试 |
| `RECLAIMING` | false | 已进入回收链路（含回收失败待重试），续期迟到；应重试回收而非续期 |
| `TERMINAL` | false | 已 RELEASED / RECLAIMED，只能重新申请 |

并发收敛：续期与回收共用每环境互斥锁并配合 CAS。临界点处先拿到结论的一方生效；
后到方要么按已有结论一致重放（同幂等标识），要么被明确判为迟到（`RECLAIMING` /
`TERMINAL` 等），排队穿过回收临界区的续期在回收收敛后只会看到终态，不可能把
已回收 / 回收中的环境拉回可用。

服务日志统一打印 `op`（操作类型）、`idempotencyId`、`env`、`owner`、
`decision` 与 `basis`（判定依据），例如：

```
op=ACQUIRE idempotencyId=.. env=e1 owner=alice decision=ACCEPT_NEW leaseState=ACTIVE basis=ownerQuota 1/2 globalQuota 1/4
op=ACQUIRE_REPLAY idempotencyId=.. decision=ACCEPT_REPLAY basis=idempotentRecord
op=RENEW idempotencyId=.. env=e1 owner=alice decision=ACCEPT_NEW basis=activeLeaseExtended oldExpireAt=.. newExpireAt=.. extend=.. quotaUnchanged global=1/4
op=RENEW_REPLAY idempotencyId=.. decision=ACCEPT_REPLAY basis=idempotentRecord
op=RENEW idempotencyId=.. env=e1 owner=bob decision=REJECT reason=NOT_OWNER retryable=false basis=..
op=RECLAIM env=f1 decision=FAILED_RETRYABLE basis=模拟回收中途失败 state=RECLAIMING
op=RECLAIM env=f1 decision=RECLAIMED basis=reclaimActionSucceeded quotaReleased=true
```

## 5. HTTP 接口

```
POST /api/envs/acquire            # body: {idempotencyId, owner, envName, ttlMillis}
POST /api/envs/{envName}/renew    # body: {idempotencyId, owner, extendMillis}
POST /api/envs/{envName}/release  # body: {owner}
POST /api/envs/{envName}/reclaim  # 幂等、可重试
POST /api/envs/sweep              # 扫描并回收所有到期/回收中的租约
```

续期响应体与申请一致（`{lease, replayed, reason}`）；拒绝时为
`{type:"renewal_rejected", reason, retryable, message}`。

应用默认是纯内存状态，重启即清空。启动：`mvn spring-boot:run`。

## 6. 验证方法

仅依赖本地 Maven 与 JDK 21，离线可跑（依赖已在本地仓库）：

```bash
mvn -o test
```

覆盖场景（`EphemeralEnvServiceTest` 12 例 + `RenewServiceTest` 16 例
+ `EnvControllerTest` 3 例 + 应用上下文 1 例，共 32 例）：

1. **幂等**：同一幂等标识串行 / 12 线程并发重复提交，只有一次生效，全部拿到
   同一份首次租约；被拒绝的首次结论也会一致重放。
2. **并发申请**：16 线程争抢不同环境，成功数严格等于全局上限，绝无超发；
   拒绝原因只落在全局维度。
3. **持有者争用**：不同持有者不能覆盖他人租约；释放后才能交接。
4. **两级配额**：持有者超额与全局超额给出不同拒绝原因。
5. **回收失败链路**：前两次回收注入故障 → 停留 `RECLAIMING`、配额不提前释放；
   第三次成功 → `RECLAIMED`；重复/迟到回收不回退。
6. **并发回收**：10 线程并发回收同一环境，回收动作只执行 1 次。
7. **守恒断言**：混合释放/回收后 `countGlobal()==0`，终态计数
   `RELEASED=2 / RECLAIMED=2`，终态环境可被重新申请；全程配额与租约数量守恒。
8. **续期成功 / 累加**：到期时刻在原到期点上顺延，多次续期累加，流逝时间不被补回；
   续期后旧 TTL 到期点回收被“尚未到期”挡下，超过新到期点才收敛。
9. **续期幂等重放**：同一标识串行 / 12 线程并发，只顺延一次，全部拿到同一份首次
   续期租约；首次拒绝（越权 / 过期 / 回收中 / 终态）也一致重放；标识绑定冲突可区分。
10. **续期越权 / 过期 / 中间态**：`NOT_OWNER`、`ALREADY_EXPIRED`、`RELEASING`、
    `RECLAIMING`、`TERMINAL`、`LEASE_NOT_FOUND` 六类原因各自可区分、结论确定；
    过期后 / 终态后补续期无法救活。
11. **回收失败待重试期间续期**：停留 `RECLAIMING` 时续期得到 `RECLAIMING` 明确拒绝
    （非假成功），状态不被拉回；回收收敛后续期再得 `TERMINAL`。
12. **续期与回收并发交错**：用可阻塞回收模拟器构造“回收动作进行中到达的续期”，
    先到的回收收敛为 `RECLAIMED`，排队续期只拿到终态拒绝，回收动作恰好执行一次。
13. **续期高并发守恒**：12 线程混合合法 / 越权续期，以及续期 + 他人申请 + 释放 +
    到期扫描交错，断言持有者维度与全局维度占用数量前后严格守恒，状态计数不漂移。
14. **HTTP 语义**：续期 200 / `replayed` 重放一致；越权 409 `NOT_OWNER`、
    过期 409 `ALREADY_EXPIRED`，与既有 rejected/conflict/reclaim_failed 可区分。

控制台与 surefire 报告中的 `op=...` / `[ASSERT] ...` 行会打印每个操作的
幂等标识、操作类型与判定依据。
