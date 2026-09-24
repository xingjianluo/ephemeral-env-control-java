# 临时环境生命周期管控（本地状态模拟）

在 Spring Boot 骨架之上实现的一套临时环境申请 / 持有 / 过期 / 回收管控流程。
全部状态保存在进程内（`ConcurrentHashMap`），回收动作由可注入的本地模拟器
`CountingFailureSimulator` 执行，**不依赖任何外部服务或真实凭据**。

## 1. 租约状态机

```
                         acquire 成功（配额通过 + 环境空闲/终态）
        (不存在 / RELEASED / RECLAIMED) ───────────────────────► ACTIVE
                                                                   │
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

- `ACTIVE`：持有中。未到期且持有者匹配才能 `release`；TTL 到期后只能 `reclaim`。
- `EXPIRED`：检测态。到期在 `reclaim/sweep` 时通过 CAS 懒标记，不做后台线程。
- `RELEASING / RECLAIMING`：中间态，**仍然占用配额**（资源尚未真正归还）。
  - `RECLAIMING` 表示回收动作中途失败，**不回退**到 EXPIRED，允许且应当重试。
- `RELEASED / RECLAIMED`：终态。重复释放 / 重复回收 / 迟到回收直接返回首次结论
  （`replayed=true`），状态绝不回退；终态环境可以被重新申请。

状态流转全部通过存储层单 key 原子 `compute`（CAS 断言）完成；释放/回收另加
**每环境互斥锁**，保证重复、迟到、并发回收只会有一条链路真正执行回收动作；
申请走**全局申请锁**，使“配额判定 + 环境争用判定 + 建租约 + 写幂等结论”成为
一个临界区，杜绝并发超发与相互覆盖。

## 2. 幂等规则

- 申请必须携带 `idempotencyId` 与 `owner`。
- 首次结论（**成功或拒绝**）永久记录；相同幂等标识重复提交：
  - 返回与首次一致的结果（同一份租约 / 同一个拒绝原因），`replayed=true`；
  - 不重复占用或释放配额；
  - 若同一幂等标识绑定的 `owner/envName` 与首次不同，返回可重试的 `conflict`。
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
| 回收中途失败 | `ReclaimFailureException`，HTTP 202 `type=reclaim_failed` | true | 停留 RECLAIMING，重试回收即可收敛 |

服务日志统一打印 `op`（操作类型）、`idempotencyId`、`env`、`owner`、
`decision` 与 `basis`（判定依据），例如：

```
op=ACQUIRE idempotencyId=.. env=e1 owner=alice decision=ACCEPT_NEW leaseState=ACTIVE basis=ownerQuota 1/2 globalQuota 1/4
op=ACQUIRE_REPLAY idempotencyId=.. decision=ACCEPT_REPLAY basis=idempotentRecord
op=RECLAIM env=f1 decision=FAILED_RETRYABLE basis=模拟回收中途失败 state=RECLAIMING
op=RECLAIM env=f1 decision=RECLAIMED basis=reclaimActionSucceeded quotaReleased=true
```

## 5. HTTP 接口

```
POST /api/envs/acquire            # body: {idempotencyId, owner, envName, ttlMillis}
POST /api/envs/{envName}/release  # body: {owner}
POST /api/envs/{envName}/reclaim  # 幂等、可重试
POST /api/envs/sweep              # 扫描并回收所有到期/回收中的租约
```

应用默认是纯内存状态，重启即清空。启动：`mvn spring-boot:run`。

## 6. 验证方法

仅依赖本地 Maven 与 JDK 21，离线可跑（依赖已在本地仓库）：

```bash
mvn -o test
```

覆盖场景（`EphemeralEnvServiceTest` 12 例 + `EnvControllerTest` 2 例）：

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

控制台与 surefire 报告中的 `op=...` / `[ASSERT] ...` 行会打印每个操作的
幂等标识、操作类型与判定依据。
