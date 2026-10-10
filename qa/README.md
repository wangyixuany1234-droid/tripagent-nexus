# TripAgent Nexus 自动化测试体系

`qa/` 是全仓测试控制面。业务测试继续放在各模块原有目录，统一阈值、合成数据、
高风险测试门禁、执行脚本和脱敏报告集中在这里维护。

## 测试分层

| 层级 | 工具 | 主要范围 |
| --- | --- | --- |
| Java | JUnit 5、Spring Boot Test、RestAssured、WireMock | 领域规则、HTTP 边界、幂等并发、异步恢复、Agent/MCP |
| Python | pytest、pytest-asyncio、httpx | OCR、检索、GraphRAG、用户隔离、认证与注入 |
| 前端 | Vitest、React Testing Library、Playwright、axe-core | 状态投影、表单、主流程、异常恢复、响应式与可访问性 |
| 契约 | OpenAPI Validator、Redocly、AsyncAPI、Schemathesis | 冻结兼容性、当前契约、SDK 漂移、动态模糊测试 |
| 性能 | k6 | 并发创建、幂等重放、写后读一致性、冒烟、负载与长稳 |
| 故障 | Toxiproxy、Testcontainers | AI 服务和 PostgreSQL 超时、降级、恢复与重放 |
| 安全 | Semgrep、Gitleaks、Trivy、Dependency-Check、ZAP | SAST、密钥、依赖、镜像/配置和动态扫描 |

## 快速开始

安装本地测试依赖：

```bash
./qa/scripts/bootstrap.sh
```

统一入口：

```bash
make test-unit
make test-integration
make test-contract
make test-e2e
make test-security
make test-intelligence QA_RUN_ID=stage10-unique-run
make test-frontend-stage11 QA_RUN_ID=stage11-unique-run
make test-observability-stage12 QA_RUN_ID=stage12-unique-run
make test-performance
make test-all
```

`make test-performance` 默认只执行 `smoke`。Load、Stress、Spike、Soak
和 SSE 必须同时设置 `QA_HIGH_LOAD_APPROVED=true` 与
`QA_MOCK_EXTERNAL_TOOLS_CONFIRMED=true`。完整入口和覆盖参数见
`qa/performance/README.md`。

统一测试环境和数据：

```bash
make test-data-init
make test-env-up
make test-env-down
make test-env-reset
```

`test-env-up` 使用 `compose.yaml` 与 `qa/compose.qa.yaml`，固定启用
`test,mock-model`、Mock OCR、Mock 检索、内存图存储和 Mock MCP。PostgreSQL、
Redis、Kafka、Neo4j、WireMock 与 Toxiproxy 只暴露到回环地址。LanceDB 数据位于
`.runtime/test-env/lancedb`，销毁环境时一并清理。

`test-integration` 默认执行 PostgreSQL、Redis、Kafka、Neo4j 和 Toxiproxy 的
Testcontainers 测试。仅在明确记录未执行原因时可临时设置
`SKIP_TESTCONTAINERS=true`。

兼容的底层入口仍可直接调用：

```bash
RUN_TESTCONTAINERS=true ./qa/scripts/run_all.sh
./qa/scripts/run_chaos.sh
BASE_URL=http://127.0.0.1:8080 ./qa/scripts/run_performance.sh smoke
SOAK_TARGET_CONTAINER=tripagent-nexus-qa-trip-agent-api-1 ./qa/scripts/run_soak.sh
RUN_ZAP=true BASE_URL=http://127.0.0.1:8080 ./qa/scripts/run_security.sh
```

`run_security.sh` 默认以 Trivy 执行 Java、Python 和前端依赖的 High/Critical
门禁。需要补充 Dependency-Check 时，从当前进程或 CI 密钥库注入 NVD Key：

```bash
RUN_DEPENDENCY_CHECK=true NVD_API_KEY="$NVD_API_KEY" \
  ./qa/scripts/run_security.sh
```

脚本仅把环境变量名传给 Maven，不会把 Key 放入命令、日志或 JSON 报告。

动态 Schemathesis 测试要求本地 API 已启动：

```bash
RUN_CONTRACT_FUZZ=true \
CONTRACT_BASE_URL=http://127.0.0.1:8080 \
./qa/scripts/run_contract.sh
```

## 强制门禁

- 每个默认入口先执行 test Profile 隔离检查；远程数据库、缓存、消息、图数据库和
  外部 HTTP 地址会被拒绝。
- 性能、故障和动态安全测试默认只允许回环地址。
- 远程测试环境必须显式设置 `QA_AUTHORIZED_TEST_ENV=true`。
- DeepSeek、地图和天气域名始终禁止成为压力或扫描目标。
- k6 在开始负载前校验后端 `providerMode` 必须为 `MOCK`。
- 真实模型仅由 `run_real_model_smoke.sh` 触发，必须设置
  `REAL_MODEL_TEST_ENABLED=true`，并受集中配置的并发和调用次数上限约束。
- 所有测试数据来自 `qa/fixtures/` 或测试内的明确合成数据。
- 固定随机种子由 `qa/fixtures/manifest.json` 定义，当前值为 `20260908`。
- Python 测试为每个用例创建独立 LanceDB 目录，并在用例结束后删除。
- `qa/wiremock/` 可通过 `X-QA-Scenario` 控制成功、超时、限流、服务异常和畸形响应。

## 阈值与报告

唯一阈值源为 `qa/config/thresholds.json`。前端同时记录当前不可回退基线与
目标覆盖率；基线用于 CI 阻止继续下降，目标用于阶段验收，二者不得混为一谈。

`run_with_report.py` 会把结果写入 `output/test-results/<run-id>/`：

```text
junit/       JUnit XML
json/        套件摘要、扫描结果和 fixture 索引
html/        Playwright、ZAP 等 HTML 报告
coverage/    Java、Python、前端覆盖率
performance/ k6 和长稳指标
logs/        写盘前脱敏的原始日志
artifacts/   固定数据集派生的 OCR 图片
manual-review/ 人工语义复核 JSON/CSV
```

失败摘要保留脱敏后的 `traceId`、`taskId`、请求摘要和日志位置；凭据、手机号和
证件号会在写盘前替换。运行产物不提交到源码仓库。

阶段一的审计范围、实际执行结果和 Docker 未执行项见
[测试阶段一：可测试性审计与统一测试环境验收](../docs/测试阶段一-可测试性审计与统一测试环境验收.md)。
对应机器可读汇总位于
`output/test-results/20260908T-stage1/json/stage1-summary.json`。

## 报告判定

只有实际执行且退出码为零的套件才能标记为通过。Docker、浏览器、扫描器或服务
未就绪时应记录为“未执行”，不得换成“通过”。性能报告必须同时满足 HTTP 阈值、
业务失败率和 `data_consistency_failures == 0`。

## 阶段九 AI 安全回归

```sh
make test-ai-security QA_RUN_ID=stage9-unique-run
```

入口使用合成语料 `qa/security/ai-stage9-corpus.json`，执行 Java、Python、
前端组件与桌面/移动端浏览器测试。使用 Mock 模型、独立 LanceDB、本机合成
MCP HTTP 服务和嵌入式 PostgreSQL checkpoint，不调用真实模型或第三方工具。
默认无需 Docker 或外部数据库服务。

每次必须使用新的运行目录；JUnit、脱敏日志、源文件指纹、失败与覆盖缺口写入
`output/test-results/<run-id>/`。失败、跳过、缺少批次、用例数量变化或未验收
缺口均阻断完整阶段门禁。即使所有可执行断言通过，只要存在 `NOT_EXECUTED`
缺口仍预期非零退出；不能删除失败断言或把未执行项改成通过。

修改 Prompt、模型适配器、检索策略或工具路由后运行同一命令，并比较逐用例
结果。Mock 回归不能证明真实模型的语义鲁棒性；真实模型测试仍需单独授权。
范围和当前问题见 [阶段九验收报告](../docs/测试阶段九-AI安全回归验收.md)。

## 阶段十智能能力评测

```sh
make test-intelligence QA_RUN_ID=stage10-unique-run
```

入口使用 `qa/fixtures/evaluation/stage10-v1.json` 固定合成数据集和 seed
`20260908`，连续运行三轮 OCR、RAG、Agent 与行程质量评测。Prompt/模型版本、
检索参数、随机配置、逐轮原始观测、指标分子分母、JUnit 和波动统计均写入
`output/test-results/<run-id>/`。

Mock 与真实模型报告严格分离。默认不会调用真实模型；当前 runner 只生成
`NOT_EXECUTED` 的真实模型报告。金额、时间、冲突、排名和 Schema 指标由程序规则
或固定金标计算，不使用待测模型自评。`manual-review/` 为 OCR 与 RAG 的全量人工
语义复核入口。

即使所有可执行批次通过，只要质量阈值未确认、人工复核未完成或真实模型未授权，
完整门禁仍返回非零并标记为 `BLOCKED_FOR_ACCEPTANCE`。当前实测基线与失败样本见
[测试阶段十验收报告](../docs/测试阶段十-智能能力评测验收.md)。

## 阶段十一前端稳定性与可访问性

```sh
make test-frontend-stage11 QA_RUN_ID=stage11-unique-run
```

入口先构建 `apps/web`，再运行 `apps/web/playwright.stage11.config.ts`。矩阵覆盖
Chromium、Firefox、WebKit 与 375x667、390x844、768x1024、1366x768、1440x900、
1920x1080 视口；Mock 数据包含 14 天行程、首日 30 项、50 个冲突、100 条证据和
20 个历史版本。

测试结果写入 `output/test-results/<QA_RUN_ID>/json/stage11-summary.json`。
Chromium/WebKit 可执行矩阵已通过；当前 macOS / TRAE 沙箱中 Firefox Nightly 在
启动阶段无法访问或识别 profile 目录，门禁标记为 `BLOCKED_ENVIRONMENT`，不能
改写为通过。真实后端小流量稳定性验证必须单独执行并出具独立报告。

## 阶段十二可观测性与告警

```sh
make test-observability-stage12 QA_RUN_ID=stage12-unique-run
```

入口使用 `qa/config/stage12-observability.json` 作为唯一验收配置，离线检查
OpenTelemetry Collector、Trace 覆盖、HTTP/Kafka/log 关联、错误 Span、指标源静态
证据、Prometheus 告警规则和隐私脱敏扫描。模拟故障只验证阈值和规则形态，不连接
真实 Prometheus 或 Alertmanager。

结果写入 `output/test-results/<QA_RUN_ID>/json/stage12-summary.json` 与
`stage12-alert-simulation.json`。当前离线审计和 9 类模拟故障告警已通过，但真实
Prometheus / Alertmanager 小流量故障注入、恢复和日志/指标/Trace 互相关联仍未执行，
完整门禁标记为 `BLOCKED_FOR_REAL_VALIDATION`，不能改写为通过。

真实验证必须单独授权并写入独立证据目录，至少包含正常流量误报 soak、9 类故障触发、
恢复验证、Alertmanager 通知内容核验和导出产物脱敏扫描。Mock / 模拟报告不得替代
真实告警验收。范围和当前缺口见
[测试阶段十二验收报告](../docs/测试阶段十二-可观测性与告警验收.md)。
