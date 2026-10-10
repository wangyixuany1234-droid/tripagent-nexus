# TripAgent Nexus

TripAgent Nexus 是面向复杂自由行规划的多智能体旅行平台。业务服务采用
Java 21、Spring Boot 3.5 和 Spring AI，AI/OCR/检索服务采用 Python 3.12
与 FastAPI，持久化和消息基础设施包括 PostgreSQL、Redis、Kafka、Neo4j
与 LanceDB。

## 当前状态

平台核心业务闭环已完整实现：旅行需求建模与可恢复草稿、订单文档
（PNG/JPEG/WEBP/PDF）安全检查与 OCR 解析、人工字段复核、八个 Agent 协作的
行程生成、方案比较与地图/时间线联动、预算分析与冲突证据、不可变版本重规划和
费用操作人工确认、行程工作台、偏好与记忆管理、结构化反馈、OIDC 登录与资源
归属授权、受控开发者追踪与系统状态页。公共 API v1 契约已冻结，前后端共用
生成 SDK 与兼容性基线。

最近一轮全量验证（2026-09-20 至 2026-09-28，Windows + Docker 本地环境）结果：

- 安全扫描：Secret 扫描 0 泄露、SAST 0 findings；Trivy 依赖与两个服务镜像
  扫描 Critical/High 均为 0，npm audit 仅剩 3 个 moderate。
- 功能回归：Java 单元与集成、OpenAPI/AsyncAPI 契约一致性、FastAPI pytest、
  前端 Vitest 全部通过；Playwright 端到端执行的 28 项全部通过、0 失败
  （另 22 项需真实后端或性能环境，本轮未执行）。
- 已修复缺陷：此前"过期会话重定向页缺少返回并重新登录入口、return path 保护
  失败"已修复，并有专用回归测试覆盖。
- 并发与故障：同资源 20 并发竞态测试符合预期（1 次成功写、19 次幂等或乐观锁
  拒绝、0 重复资源、0 业务错误）；消息重试、死信与任务恢复混沌测试通过。
- 性能：k6 Smoke 通过并产出完整资源与延迟指标；Load 完成约 143 req/s 实测并
  产出指标，但部分阈值未达标，判级为未通过。
- 前端稳定性：Mock 路由隔离矩阵通过（0 失败、0 环境阻塞），覆盖三浏览器、
  六档视口与七种网络模式。

以下仍为未完成的真实环境验收，其结论不得外推为生产可用：真实模型、真实 OCR
与人工语义复核（质量阈值未定义）；真实 Prometheus / Alertmanager 小流量故障
注入；最终数据一致性复验（需授权数据库）；负载阈值与前端覆盖率长期目标
（当前行覆盖率约 46%）。

## 核心能力

- 旅行需求建模、参数校验和 Prompt Injection 防护。
- PNG、JPEG、WEBP、PDF 安全检查、OCR、多模态订单抽取与人工字段修正。
- 公共知识库和用户记忆库物理隔离，支持 LanceDB 混合召回、GraphRAG、
  查询改写、过滤、Cross-Encoder 重排和证据压缩。
- Requirement、Research、Transport、Accommodation、Recommendation、
  Budget、Review、Coordinator 八个独立 Agent。
- 官方 MCP SDK 工具调用与官方 A2A SDK 任务委派；客户端固定服务身份、工具
  manifest 和返回 Schema，Agent checkpoint 提供持久化去重与并发重放保护。
- `BigDecimal` 预算计算和 Java 确定性规则引擎。
- PostgreSQL Outbox/Inbox、Kafka 有界重试、死信、Redis 热状态和任务恢复。
- SSE 进度推送、轮询、断点续传和一致的终态资源 ID。
- OpenTelemetry 全链路追踪、Guardrails、Human-in-the-loop 和版本化评测回放。
- React 旅行指挥舱、经后端代理的真实世界地图、全局错误/Trace 处理和
  可访问视觉系统。
- 可恢复的旅行需求草稿、订单 OCR 人工复核和后端驱动的 Agent 执行进度。
- 同一方案范围内的地图、时间线、预算、冲突和证据视图，以及刷新恢复和
  版本隔离。
- 按目的地、日期和状态筛选的行程工作台，以及后端驱动的任务取消和继续处理。
- 用户主动偏好和模型推断偏好分层管理，支持修改、拒绝、删除和后端记忆清理。
- 面向方案、地点、预算和推荐依据的结构化反馈，以及受规则约束的偏好沉淀。
- OIDC Authorization Code + PKCE 浏览器登录、后端 JWT issuer/audience/JWK
  校验，以及覆盖旅行、方案、资料、任务、反馈和工作台的统一资源归属授权。
- 受控开发者追踪和脱敏系统状态，管理员角色只取自已验证 JWT，不向普通页面
  暴露内部标识、连接信息或凭据。
- 用户界面不展示运行模式、协议、内部标识、状态码或原始异常；诊断信息仅
  保留在受控日志和可观测链路中。

## 仓库结构

```text
apps/trip-agent-api                 Spring Boot 公共 API 与 Agent 编排
services/ai-retrieval-service       FastAPI OCR、LanceDB、Neo4j 与重排
packages/api-contract               当前 OpenAPI、AsyncAPI 与 JSON Schema
contracts/openapi/tripagent-v1.yaml 公共 OpenAPI v1 兼容性基线
apps/web                            React 前端与自动生成的 TypeScript Fetch SDK
infrastructure/otel-collector       OpenTelemetry Collector 配置
docs                                中文架构、领域、状态机与契约文档
```

## 接口与兼容性

- 公共 API 固定使用 `/api/v1`。
- 当前公共契约为
  [`packages/api-contract/openapi.yaml`](packages/api-contract/openapi.yaml)；
  [`contracts/openapi/tripagent-v1.yaml`](contracts/openapi/tripagent-v1.yaml)
  作为不可破坏的兼容性基线。
- 已冻结字段不得删除或改名；新增字段必须默认可选。
- 除健康检查、系统状态和地图瓦片等明确公开操作外，公共 API 要求 OIDC Bearer
  JWT；后端校验签名、issuer、audience 和 UUID `sub`。
- 所有写操作要求 `Idempotency-Key`。
- 长任务返回 `taskId`、`traceId`，并同时支持轮询和 SSE。
- 错误统一返回 `code`、`message`、`details`、`traceId`、`timestamp`。
- 浏览器只能请求 Spring Boot；`/internal/v1`、MCP 和第三方服务均为后端边界。
- 地图瓦片通过 `GET /api/v1/maps/tiles/{zoom}/{x}/{y}.png` 获取，前端
  `MapsApi` 不直接连接地图供应商。

## 本地启动

### 演示模式

不需要模型密钥。没有可用 OIDC 提供方时，必须显式关闭前后端用户认证；该配置
只适用于本机演示和自动化测试，不得用于共享或生产环境：

```bash
export ASYNC_OPERATIONS_TOKEN="$(openssl rand -hex 32)"
export AI_SERVICE_TOKEN="$(openssl rand -hex 32)"
export MCP_SERVICE_TOKEN="$(openssl rand -hex 32)"
export USER_AUTH_ENABLED=false
export VITE_USER_AUTH_ENABLED=false
SPRING_PROFILES_ACTIVE=docker,mock-model \
MODEL_GATEWAY_PROVIDER=mock \
DOCUMENT_PARSER_MODE=mock \
RETRIEVAL_PROVIDER_MODE=mock \
MCP_PROVIDER_MODE=MOCK \
PLANNING_PROVIDER_MODE=MOCK \
docker compose up --build -d
```

### 真实模式

必须从服务端密钥管理系统或当前进程环境注入 `DEEPSEEK_API_KEY`，不得写入
源码、`.env`、镜像、日志、测试快照或任何前端变量。还必须配置可信 OIDC
提供方；后端 issuer 与 JWK 地址、前端 authority 必须属于同一身份域：

```bash
: "${DEEPSEEK_API_KEY:?请先从服务端密钥管理系统注入 DEEPSEEK_API_KEY}"
: "${AUTH_ISSUER_URI:?请配置 OIDC issuer}"
: "${AUTH_JWK_SET_URI:?请配置 OIDC JWK Set URL}"
: "${VITE_OIDC_AUTHORITY:?请配置前端 OIDC authority}"
: "${VITE_OIDC_CLIENT_ID:?请配置前端 OIDC client ID}"
export ASYNC_OPERATIONS_TOKEN="$(openssl rand -hex 32)"
export AI_SERVICE_TOKEN="$(openssl rand -hex 32)"
export MCP_SERVICE_TOKEN="$(openssl rand -hex 32)"
export USER_AUTH_ENABLED=true
export VITE_USER_AUTH_ENABLED=true
export AUTH_AUDIENCE=tripagent-api
export VITE_OIDC_SCOPE="openid profile"
SPRING_PROFILES_ACTIVE=docker \
MODEL_GATEWAY_PROVIDER=deepseek \
DOCUMENT_PARSER_MODE=deepseek \
RETRIEVAL_PROVIDER_MODE=deepseek \
MCP_PROVIDER_MODE=LIVE \
PLANNING_PROVIDER_MODE=LIVE \
docker compose up --build -d
```

检查完整系统：

```bash
docker compose ps
curl --fail http://localhost:5173/healthz
curl --fail http://localhost:8080/actuator/health/readiness
```

服务入口：

- React 指挥舱：<http://localhost:5173>
- 旅行需求向导：<http://localhost:5173/trips/new>
- 订单资料中心：<http://localhost:5173/documents>
- Agent 生成控制台：<http://localhost:5173/tasks>
- 核心结果页：`http://localhost:5173/plans/<plan-id>`
- 行程工作台：<http://localhost:5173/workbench>
- 用户偏好与记忆：<http://localhost:5173/preferences>
- 反馈中心：<http://localhost:5173/feedback>
- 系统状态：<http://localhost:5173/system-status>
- 开发者追踪：<http://localhost:5173/developer/traces>
- React 组件预览：<http://localhost:5173/design-system>
- Spring readiness：<http://localhost:8080/actuator/health/readiness>
- Spring liveness：<http://localhost:8080/actuator/health/liveness>
- MCP Streamable HTTP：<http://localhost:8080/mcp>
- FastAPI 内部健康检查：<http://localhost:8000/internal/v1/health>
- Jaeger：<http://localhost:16686>

停止服务但保留数据：

```bash
docker compose down
```

只有明确需要删除本地数据库、队列、图、向量和追踪数据时才执行：

```bash
docker compose down -v
```

仅进行前端开发时：

```bash
npm --prefix apps/web install --no-audit --no-fund
VITE_API_BASE_URL=http://localhost:8080 \
VITE_USER_AUTH_ENABLED=true \
VITE_OIDC_AUTHORITY=https://your-oidc-provider.example \
VITE_OIDC_CLIENT_ID=tripagent-web \
VITE_OIDC_SCOPE="openid profile" \
  npm --prefix apps/web run dev -- --host 127.0.0.1
```

OIDC 客户端必须允许
`http://localhost:5173/auth/callback`、`/auth/silent-callback` 和
`/auth/logout-callback` 对应的完整回调地址。浏览器会把授权码和 PKCE 状态保存在
`sessionStorage`，API 请求统一携带 Bearer Token；切换用户或会话失效时会清理本地
旅行恢复索引。仅做无后端数据的 UI 调试时可显式设置
`VITE_USER_AUTH_ENABLED=false`。

地图默认使用 OpenStreetMap。生产环境应设置可识别应用和联系渠道的
`MAP_TILE_USER_AGENT`，并按供应商同步配置 `MAP_TILE_BASE_URL` 与
`ALLOWED_EXTERNAL_HOSTS`；浏览器端无需地图密钥。

## 自动化测试

安装测试依赖：

```bash
./qa/scripts/bootstrap.sh
```

使用统一 Make 入口：

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

一键创建、销毁或重置本地测试环境：

```bash
make test-env-up
make test-env-down
make test-env-reset
```

`make test-performance` 默认只运行本地 smoke 场景。负载和长稳测试必须显式
执行：

```bash
QA_PROFILE=load make test-performance
./qa/scripts/run_soak.sh
```

大规模测试默认使用 MockModelGateway 和 Mock MCP。真实模型测试必须显式
设置 `REAL_MODEL_TEST_ENABLED=true`，并受并发 1、最多 5 次调用的集中阈值
约束。任何性能、故障或安全脚本都不得指向地图、天气、DeepSeek 等第三方服务。

前端稳定性矩阵使用 Mock 路由隔离数据，覆盖 Chromium、Firefox、WebKit 与
375x667 至 1920x1080 视口，并生成
`output/test-results/<QA_RUN_ID>/json/stage11-summary.json`；最近一次执行
0 失败、0 环境阻塞。该结果不替代真实后端小流量报告。

可观测性与告警验收使用离线证据审计和模拟阈值验证，覆盖 Trace 组件、
HTTP/Kafka/log 关联、指标源、Prometheus 告警规则和隐私脱敏扫描，并生成
`output/test-results/<QA_RUN_ID>/json/stage12-summary.json`；真实
Prometheus / Alertmanager 小流量故障注入未执行，该结果不替代真实告警触发、
恢复和日志/指标/Trace 互相关联验证。

最终全量回归按 Secret Scan、SAST、依赖/镜像、单元、契约、集成、E2E、AI、
安全、性能、负载、故障恢复和数据一致性顺序执行，证据统一保存于
`output/test-results/<QA_RUN_ID>/`，最近一轮结论见上文"当前状态"。

统一阈值位于 `qa/config/thresholds.json`，合成数据清单位于
`qa/fixtures/manifest.json`。JUnit XML、JSON、HTML、覆盖率、性能结果和脱敏日志
统一保存到 `output/test-results/<QA_RUN_ID>/`。

## 后端验证

Java 单元和嵌入式集成测试：

```bash
mvn -B -ntp clean verify spotless:check
```

PostgreSQL、Redis、Kafka、Neo4j Testcontainers：

```bash
mvn -B -ntp -Ptestcontainers verify
```

FastAPI：

```bash
services/ai-retrieval-service/.venv/bin/ruff check \
  services/ai-retrieval-service
services/ai-retrieval-service/.venv/bin/ruff format --check \
  services/ai-retrieval-service
services/ai-retrieval-service/.venv/bin/pytest -q \
  services/ai-retrieval-service
```

契约：

```bash
npx --yes @redocly/cli lint contracts/openapi/tripagent-v1.yaml
npx --yes @redocly/cli lint packages/api-contract/ai-internal-openapi.yaml
ASYNCAPI_METRICS_CONFIG_PATH=/tmp/tripagent-asyncapi-analytics \
  npx --yes @asyncapi/cli validate packages/api-contract/asyncapi.yaml \
  --fail-severity error --suppressWarnings asyncapi2-message-messageId
```

生成并编译 TypeScript SDK：

```bash
./scripts/generate-typescript-sdk.sh
npm --prefix apps/web/src/api/generated install \
  --ignore-scripts --package-lock=false --no-audit --no-fund
npm --prefix apps/web/src/api/generated run build
```

前端：

```bash
npm --prefix apps/web run typecheck
npm --prefix apps/web test
npm --prefix apps/web run build
npm --prefix apps/web run test:e2e
```

敏感信息检查：

```bash
gitleaks dir . --config .gitleaks.toml --no-banner --redact
```

## 主流程

端到端回归覆盖以下真实后端路径：

1. 创建旅行并保存需求。
2. 上传脱敏订单。
3. 等待 OCR/规则解析进入 `WAITING_CONFIRMATION`。
4. 人工修正缺失时间字段。
5. 发起异步方案生成。
6. 通过 SSE 获取终态事件，并通过轮询获取相同任务终态。
7. 获取 `BALANCED` 和 `LOW_TRANSIT` 两个方案。
8. 查看并验证确定性冲突。
9. 锁定交通、移除餐饮并发起重规划。
10. 确认新版本方案。
11. 提交评分和反馈。
12. 在行程工作台继续、筛选或取消任务。
13. 查看、拒绝或删除由反馈形成的推断偏好。

## 数据与安全约束

- 金额使用 `BigDecimal`/`NUMERIC(19,4)`，时间戳使用 UTC。
- 用户记忆检索必须同时具备可信 `tenantId` 和 `userId`。
- 公共知识索引使用 `city + chunkId` 复合键，避免跨城市同名分块相互覆盖。
- A2A 消息仅携带目标 Agent 所需的最小上下文。
- 日志和 Trace 禁止记录 API Key、Authorization、原始图片、OCR 原文、
  完整订单号、证件号和未脱敏私人偏好。
- Agent 只能生成订单、支付、退订和真实成本动作候选；执行前必须经过
  未过期、金额和币种完全匹配的人工确认。
- 所有重试都有最大次数、超时和最大退避，不存在无限重试。

## 文档

- [总体架构](docs/系统架构.md)
- [领域模型](docs/领域模型.md)
- [任务状态机](docs/状态机.md)
- [错误码](docs/错误码.md)
- [Agent 中间步骤恢复契约](docs/Agent中间步骤恢复契约.md)

## 验证边界与发布约束

- 当前所有通过结论均来自本地 Mock 或离线环境（MockModelGateway、Mock 外部
  工具、Mock 路由隔离、离线可观测性审计），仅证明工程正确性与回归稳定性。
- 费用确认仅记录候选授权，不会自动支付或调用真实供应商；演示数据不得解释为
  真实第三方调用成功。
- 智能能力 Mock 基线不得替代真实模型、真实图片和人工语义验收；可观测性离线
  审计不得替代真实 Prometheus / Alertmanager 小流量故障注入。
- 发布门禁保持：Critical/High 漏洞为 0、核心端到端回归通过、负载阈值达标、
  最终数据一致性复验通过、真实环境验收齐备；任一未满足即不得发布。
