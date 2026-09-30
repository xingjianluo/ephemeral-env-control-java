# 临时环境生命周期管控（本地状态模拟）

在 Spring Boot 骨架之上实现的一套临时环境申请 / 持有 / 续期 / 过期 / 回收管控流程。
全部状态保存在进程内（`ConcurrentHashMap`），回收动作由可注入的本地模拟器
`CountingFailureSimulator` 执行，**不依赖任何外部服务或真实凭据**。

## 1. 租约状态机

```
                         acquire 成功（配额通过 + 环境空闲/终态）
        (不存在 / RELEASED / RECLAIMED) ───────────────────────► ACTIVE
                                                                   │  ▲
                                                                   │  └──────── renew（持有者，未到期）：expireAt += extendMillis
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
                                                   （EXPIRED / RECLAIMING / 终态上的 renew 一律拒绝，不回退）
```

关键规则：

- `ACTIVE`：持有中。未到期且持有者匹配才能 `release` / `renew`；TTL 到期后只能 `reclaim`。
- `renew`（续期）：仅在 `ACTIVE 且 now < expireAt` 时允许，把 `expireAt` 顺延
  `extendMillis`，**不改变状态、不改变持有者、不改变占用数量**。
- `EXPIRED`：检测态。到期在 `reclaim/sweep` 或迟到的 `renew` 时通过 CAS 懒标记，不做后台线程。
- `RELEASING / RECLAIMING`：中间态，**仍然占用配额**（资源尚未真正归还）。
  - `RECLAIMING` 表示回收动作中途失败，**不回退**到 EXPIRED，允许且应当重试。
  - 中间态上的续期拿到可重试的明确结论（`TRANSITION_IN_PROGRESS`），**绝不**把环境拉回 ACTIVE。
- `RELEASED / RECLAIMED`：终态。重复释放 / 重复回收 / 迟到回收 / 迟到续期直接返回首次结论
  （`replayed=true` 或确定性拒绝），状态绝不回退；终态环境可以被重新申请。

状态流转全部通过存储层单 key 原子 `compute`（CAS 断言）完成；释放 / 回收 / 续期另加
**每环境互斥锁**，保证同一环境上的释放、回收、续期彼此串行——“谁先到达临界点谁生效”，
重复、迟到、并发请求只会有一条链路真正改变状态，后来者要么一致重放、要么被判定迟到；
申请走**全局申请锁**，使“配额判定 + 环境争用判定 + 建租约 + 写幂等结论”成为
一个临界区，杜绝并发超发与相互覆盖。

## 2. 幂等规则

- 申请必须携带 `idempotencyId` 与 `owner`；续期同样必须携带 `idempotencyId`、`owner` 与顺延时长。
- 首次结论（**成功或拒绝**）永久记录；相同幂等标识重复提交：
  - 返回与首次一致的结果（同一份租约 / 同一份续期后到期时刻 / 同一个拒绝原因），`replayed=true`；
  - 不重复占用、释放配额，也不重复顺延到期时刻；
  - 若同一幂等标识绑定的 `owner/envName` 与首次不同，返回确定性冲突
    `conflict / IDEMPOTENCY_REQUEST_MISMATCH / retryable=false`，调用方应换键或改回首次参数。
- **操作命名空间隔离（申请 ↔ 续期，两个方向完全对称）**：幂等记录按操作类型
  （`ACQUIRE` / `RENEW`）分别记录，四类首次结论——**申请成功、申请被拒、续期成功、
  续期被拒——各自独立、互不串用**。同一幂等标识无论先后顺序如何，只要第二次换了一类操作，
  都只能得到同一个明确、可区分的结论：HTTP 409
  `type=conflict, reason=IDEMPOTENCY_OPERATION_MISMATCH, retryable=false`
  （服务层抛 `ConflictException(ConflictReason.IDEMPOTENCY_OPERATION_MISMATCH, …)`），
  **绝不会**返回 400 参数错误，也**绝不会**被当成另一类操作的成功重放。确切矩阵：

  | 首次使用（同标识的首次结论） | 再用该标识发起申请 | 再用该标识发起续期 |
  |---|---|---|
  | 申请成功 | `200` 重放首次租约 `replayed=true` | `409 conflict / IDEMPOTENCY_OPERATION_MISMATCH` |
  | 申请被拒 | `409 rejected / 首次拒绝原因`（一致重放） | `409 conflict / IDEMPOTENCY_OPERATION_MISMATCH` |
  | 续期成功 | `409 conflict / IDEMPOTENCY_OPERATION_MISMATCH` | `200` 重放首次续期结论 `replayed=true` |
  | 续期被拒 | `409 conflict / IDEMPOTENCY_OPERATION_MISMATCH` | `409 renew_rejected / 首次拒绝原因`（一致重放） |

  跨操作冲突时**不产生任何副作用**：不新建租约、不顺延到期时刻、不改变租约状态与持有者、
  不改变两级占用数量；已写入的首次结论记录也保持原样。调用方看到该 reason 即知应
  **更换幂等标识重试**，而不是怀疑参数非法或误以为操作已经生效。
- 释放与回收天然幂等：终态上重复调用返回首次结论，不做第二次扣减。
- **续期结论的“确定性 / 可重试”边界**：
  - 越权（`NOT_OWNER`）、已过期（`ALREADY_EXPIRED`）、终态（`ALREADY_TERMINAL`）、
    无租约（`LEASE_NOT_FOUND`）是**确定性结论**，会写入幂等记录，同键重放得到同一结论，`retryable=false`；
  - 环境正处于 `RELEASING / RECLAIMING`（含回收失败待重试）时结论尚未收敛，
    返回可重试的 `TRANSITION_IN_PROGRESS`（`retryable=true`），**不写幂等记录**；
    待状态收敛后，用同一幂等键重试会拿到当时的确定结论（成功顺延或终态拒绝）。
    这样既不会把回收中的环境“假成功”拉回可用，也不会把一个过期的临时结论永久固化。

### 2.1 续期时间语义

- 顺延基准是**当前到期时刻**：`newExpireAt = 当前 expireAt + extendMillis`，而不是 `now + extendMillis`。
  因此租约中已经流逝的时间不会因为续期被“重新续回来”。
- 多次续期在每次的当前到期时刻上**累加顺延**。
- 临界点判定 `now >= expireAt` 即视为已过期（含恰好相等）。到期后的续期先把到期的
  `ACTIVE` 懒标记为 `EXPIRED` 再确定性拒绝——**不能靠过期后补一次续期把环境救活**。

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
- **续期不触碰配额**：续期只改 `expireAt`，租约状态与持有者都不变，因此既不会多占一份
  配额，也不会归还配额；它与其他环境的申请、释放、回收并发交错时，实时统计口径天然守恒，
  不会把配额算丢或算重（见 `RenewalServiceTest` 的并发压力与守恒用例）。

## 4. 失败与冲突的区分（可解释、可重试）

| 场景 | 响应 / 异常 | retryable | 说明 |
|---|---|---|---|
| 配额超额、环境被他人占用 | `AcquireRejectedException`，HTTP 409 `type=rejected` | false | 确定性结论，不应原样重试 |
| 续期越权 / 已过期 / 终态 / 无租约 | `RenewRejectedException`，HTTP 409 `type=renew_rejected`，reason=`NOT_OWNER/ALREADY_EXPIRED/ALREADY_TERMINAL/LEASE_NOT_FOUND` | false | 确定性结论，同幂等键一致重放，续期不生效 |
| 续期时环境在释放/回收途中（含回收失败待重试） | `RenewRejectedException`，HTTP 409 `type=renew_rejected`，reason=`TRANSITION_IN_PROGRESS` | true | 结论未收敛，稍后重试拿确定结论，绝不假成功 |
| 幂等键跨操作复用（申请 ↔ 续期，任一方向） | `ConflictException`，HTTP 409 `type=conflict`，reason=`IDEMPOTENCY_OPERATION_MISMATCH` | false | 确定性冲突：标识已绑定另一类操作的首次结论，必须换键重试；不生效、不改状态/到期时刻/配额 |
| 同一幂等键重复提交但 owner/envName 不一致 | `ConflictException`，HTTP 409 `type=conflict`，reason=`IDEMPOTENCY_REQUEST_MISMATCH` | false | 确定性冲突：换键或改回首次参数 |
| 未到期回收、非持有者释放、释放/回收途中操作等 | `ConflictException`，HTTP 409 `type=conflict`，reason=`LEASE_STATE_CONFLICT` | true | 租约状态冲突，与幂等标识无关，稍后可用同一请求重试 |
| 回收中途失败 | `ReclaimFailureException`，HTTP 202 `type=reclaim_failed` | true | 停留 RECLAIMING，重试回收即可收敛 |

服务日志统一打印 `op`（操作类型）、`idempotencyId`、`env`、`owner`、
`decision` 与 `basis`（判定依据），例如：

```
op=ACQUIRE idempotencyId=.. env=e1 owner=alice decision=ACCEPT_NEW leaseState=ACTIVE basis=ownerQuota 1/2 globalQuota 1/4
op=ACQUIRE_REPLAY idempotencyId=.. decision=ACCEPT_REPLAY basis=idempotentRecord
op=RENEW idempotencyId=.. env=e1 owner=alice decision=ACCEPT basis=activeBeforeExpiry expireAt 1060000 -> 1090000 (+30000) state=ACTIVE quotaUnchanged=true
op=RENEW idempotencyId=.. env=e1 owner=alice decision=REJECT reason=ALREADY_EXPIRED retryable=false basis=租约已到期，续期迟到
op=RENEW idempotencyId=.. env=f1 owner=alice decision=RETRY basis=reclaimingInProgress
op=RECLAIM env=f1 decision=FAILED_RETRYABLE basis=模拟回收中途失败 state=RECLAIMING
op=RECLAIM env=f1 decision=RECLAIMED basis=reclaimActionSucceeded quotaReleased=true
```

## 5. HTTP 接口

```
POST /api/envs/acquire            # body: {idempotencyId, owner, envName, ttlMillis}
POST /api/envs/{envName}/renew    # body: {idempotencyId, owner, extendMillis} 在当前到期时刻上顺延
POST /api/envs/{envName}/release  # body: {owner}
POST /api/envs/{envName}/reclaim  # 幂等、可重试
POST /api/envs/sweep              # 扫描并回收所有到期/回收中的租约
```

应用默认是纯内存状态，重启即清空。启动：`mvn spring-boot:run`。

## 6. 验证方法

仅依赖本地 Maven 与 JDK（`maven.compiler.source=21`），离线可跑（依赖已在本地仓库）：

```bash
mvn -o test
```

覆盖场景（`EphemeralEnvServiceTest` 12 例、`RenewalServiceTest` 13 例、
`IdempotencyNamespaceIsolationTest` 5 例、`EnvControllerTest` 4 例，
另有 Spring 上下文 1 例，共 35 例）：

1. **幂等**：同一幂等标识串行 / 多线程并发重复提交，只有一次生效，全部拿到
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
8. **续期成功与时间语义**：在**当前到期时刻**上顺延（不是 `now+时长`），已流逝时间
   不会被续回来；多次续期累加；恰好到期点 `now==expireAt` 即判过期。
9. **续期幂等**：同一幂等标识重复 / 并发提交只顺延一次，全部返回同一到期时刻。
13. **幂等标识命名空间隔离（申请 ↔ 续期双向）**：`IdempotencyNamespaceIsolationTest`
    与 `EnvControllerTest` 覆盖四个方向——续期成功后的标识再申请、续期被拒
    （如 `NOT_OWNER`）后的标识再申请、申请成功后的标识再续期、申请被拒
    （如配额超限）后的标识再续期：
    - 服务层断言抛出 `ConflictException` 且 `reason=IDEMPOTENCY_OPERATION_MISMATCH`、
      `retryable=false`；HTTP 层断言统一为 `409 type=conflict`，
      不会出现 400 `bad_request`，也不会出现 `replayed=true` 的假成功重放；
    - 断言租约状态 / 持有者 / 到期时刻 / 持有者与全局占用数量 / 租约总数均与冲突前
      完全一致，跨操作复用不生效任何一次申请或续期；
    - 另断言该冲突与“同键参数不一致”（`IDEMPOTENCY_REQUEST_MISMATCH`）及租约状态冲突
      （`LEASE_STATE_CONFLICT`）彼此可区分，调用方可据此决定换键重试还是稍后重试。
10. **续期越权 / 过期 / 终态**：给出可区分的确定性拒绝（`NOT_OWNER / ALREADY_EXPIRED /
    ALREADY_TERMINAL`），过期环境不能被补续救活，只能走回收。
11. **续期与回收并发交错**：回收失败待重试期间续期返回可重试结论而不是假成功，
    环境绝不从 `RECLAIMING` 被拉回 `ACTIVE`；回收收敛后同键得到终态拒绝；
    并发交错下没有任何一次续期能成功回退已进入回收/终态的环境。
12. **续期并发配额守恒**：续期与申请/释放交错的高并发压力下，持有者与全局上限始终
    不被突破，实时占用数与占用态租约数始终相等；续期全程不改变占用数量。

控制台与 surefire 报告中的 `op=...` / `[ASSERT] ...` 行会打印每个操作的
幂等标识、操作类型与判定依据。
