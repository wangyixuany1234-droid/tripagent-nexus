# 阶段七补充：运行中 Agent 中间步骤续跑

## 范围

本次补齐的是 `AGENTS_RUNNING`、进度 40、主计划仍为 `GENERATING` 且尚未
写入候选计划的续跑能力。它不等于任意时点的通用流程恢复，也不承诺外部
非幂等调用恰好执行一次。

- 已持久化且通过 Schema 校验的成功或降级步骤复用原结果，不再次调用模型。
- 未完成、结果未提交的步骤可新建尝试，原执行保留中断记录。
- 取消、终态、过期、输入/租户/Prompt 版本不符及损坏结果均拒绝复用。
- 已进入计划写入阶段仍沿用安全失败或完成结果对账，禁止整单盲目重放。
- `DEGRADED` 的证据和告警保持不变，恢复不把 `PARTIAL_SUCCESS` 提升为成功。

## 设计变更

详见 [恢复契约](Agent中间步骤恢复契约.md)。

1. V11 迁移新增 `planning_checkpoint` 和 `agent_step_checkpoint`，
   分别保存输入快照及步骤完整结果，与展示用 `agent_result` 分离。
2. 步骤采用稳定逻辑 ID，指纹包含最小化输入、租户和 Prompt 版本；
   任务指纹核对用户、需求、订单、计划版本及编排配置。
3. 新执行令牌隔离旧回调。步骤结果和 Agent 展示投影在同一事务提交，
   重复完成不能覆盖原结果。
4. PostgreSQL 会话锁独占同一个任务；恢复器不抢占仍活跃的任务。
   失去锁的旧执行不能更新任务失败状态。
5. 执行锁占用连接有并发限额，最多使用 Hikari 池一半容量，为业务事务
   保留连接；Hikari 至少需要两个连接。
6. 隐私标签使用稳定枚举顺序，JSON 数值按精确数值规范化，避免进程重启后
   集合顺序或 JSONB 数值表示变化导致指纹误判。真正的内容变化仍被拒绝。
7. 检查点默认保留 24 小时，最多配置 7 天；终态清理及定时过期清理不暴露
   私有快照到 API。单个恢复失败被隔离，不再阻止整个应用就绪。

新增配置：

```properties
PLANNING_CHECKPOINT_RETENTION=24h
PLANNING_CHECKPOINT_CLEANUP_INTERVAL=1h
PLANNING_MAX_CONCURRENT_WORKFLOWS=4
```

## 真实实验

使用实际 Spring Boot 进程、官方 A2A/MCP、Kafka、PostgreSQL、Redis。
仅模型为 Mock；暂停屏障在测试代码中的模型调用内部，不修改生产 Agent。

### 串行中断

等待 Requirement 结果提交后，让 Research 进入调用屏障，再强制杀死进程。
启动新进程后恢复相同 taskId，核对 Requirement 模型调用及持久化执行数仍为 1，
Research 保留两次尝试，最后生成两份可读取的计划。

### 并行中断

让 Accommodation 暂停，同时等待 Requirement、Research、Transport、
Recommendation 均实际完成并提交，再杀死进程。恢复后四个已完成步骤的模型
调用计数均保持 1，只有被中断步骤新增尝试。

两类实验都验证原 traceId 保留、任务进度不回退、没有重复计划版本、没有遗留
RUNNING Agent/PROCESSING Inbox，且私有检查点在终态清理。

## 执行与证据

**本项限定范围验收通过。** 最终代码已执行以下测试：

| 批次 | 结果 | 证据 |
| --- | --- | --- |
| 最终 Java 全量 | 227 项，226 通过、1 项真实模型门禁跳过，零失败 | `junit/full-final` |
| 真实进程实验 | 3/3，含两个中间步骤和既有排队恢复 | `junit/process-final` |
| 检查点/Schema/恢复聚焦 | 28/28，随后全部包含于全量 | `junit/safety-final` |
| QA Harness | 49/49 | `logs/qa-harness.log` |
| 格式 | Spotless、相关 Python Ruff 通过 | `logs/java-coverage-format.log` |
| 密钥扫描 | Java 源码 1.36MB、实验证据 17.90MB，复核扫描无命中 | `json/gitleaks-source-verified.json`、`json/gitleaks-evidence.json` |

最终 Java 分层行覆盖率：核心规则 97.11%、应用服务 86.91%、Controller 85.77%，
均通过原门槛。门禁同时核对未修改 Python 的前次覆盖率，但不算作本次 Python 复测。

真实中断最终指标：

| 中断位置 | 重启至核对完成 | 已完成步骤模型次数 | 中断步骤执行尝试 |
| --- | ---: | --- | ---: |
| Research | 14120ms | Requirement：1 → 1 | 2 |
| Accommodation | 14548ms | Requirement、Research、Transport、Recommendation：各 1 → 1 | 2 |

两次均低于 30000ms 门槛；丢失任务及重复业务资源为零，输出两个有效计划，
最终状态保持 `PARTIAL_SUCCESS`。旧 Coordinator 执行记录为中断，新 Coordinator
继续编排；不得将“未重复完成步骤”理解成 Coordinator 本身没有新的执行记录。
本机为 QEMU 软件模拟，以上为单次故障恢复耗时，不是生产容量结论。

Python 服务与前端实现未修改，本次没有重跑其完整测试；仍引用此前阶段七证据，
不把此前测试数计入本次执行量。Docker 类不属于默认 Surefire 全量，3 项单列。

复跑命令（需要本地或明确授权 Docker）：

```sh
mvn -B -ntp -pl apps/trip-agent-api \
  -Dtest=AgentCheckpointSafetyIntegrationTest,AgentResumeReproductionTest,PlanningExecutionOwnershipTest test

mvn -B -ntp -pl apps/trip-agent-api \
  -Dtest=Stage7ProcessRecoveryTestcontainersIntegrationTest \
  -Dchaos.output="$PWD/output/test-results/agent-resume/experiments" test
```

`make test-chaos` 已包含这些步骤测试。Docker 不可用时 Testcontainers 可能跳过，
必须检查 JUnit 的 skipped 和实验文件，不能仅根据 Maven 退出 0 判为通过。

本轮产物目录：`output/test-results/20260913T-stage7-agent-resume/`。
逐次实验独立保存 JSON，失败不覆盖；日志、JUnit、模型调用次数文件可审计。
机器摘要 `json/stage7-summary.json` 的 `agentResume.status=PASSED`，
同时整体 `acceptanceStatus=PARTIAL`，避免掩盖阶段七其他剩余项。

## 失败与修复记录

- 初次基础设施测试因 Docker 未就绪跳过，不计通过。
- 测试屏障 Bean 与已有 Mock 都标记 Primary，导致启动失败；改为包装已选
  Mock Bean，不调整生产模型选择逻辑。
- 可选执行令牌输出为 null 违反原生成 Schema；改为省略空值，旧输入兼容。
- 实际进程重启暴露了 Set 遍历顺序及整数/小数表示造成的指纹漂移；
  增加稳定序列化回归并修复，没有跳过指纹检查。
- 早期安全用例误把异常 message 当用户提示，修正为校验既有异常码，
  未放宽异常类型或拒绝条件。
- 源码密钥初扫命中三个已有测试 Idempotency-Key；按仓库现有幂等标识规则
  复扫通过，未降低真实密钥检查规则。实验证据不排除报告目录。

## 清理

容器及卷为零，自建网络为零；`colima-s8` 已停止并删除。
本次工作区的 `.c`、`.d`、临时 Lima 工具与下载包及 DNS 配置已删除。
测试应用、QEMU、Lima 和临时 PostgreSQL 无残留运行进程；IDE Java 不受影响。
保留所有 JUnit、实验 JSON、失败日志和调用计数证据。

其余阶段七缺口（逐实验 Jaeger/浏览器验收、完整长稳资源基线）不在本次范围，
阶段七整体结论仍保留为部分验收。
