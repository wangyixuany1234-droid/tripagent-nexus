# TripAgent Nexus

TripAgent Nexus 是面向复杂自由行规划的多智能体旅行平台。业务服务采用
Java 21、Spring Boot 3.5 和 Spring AI，AI/OCR/检索服务采用 Python 3.12
与 FastAPI，持久化和消息基础设施包括 PostgreSQL、Redis、Kafka、Neo4j
与 LanceDB。

## 当前状态

阶段一至阶段十后端已经完成并冻结公共 API v1。阶段十一完成 React
前端工程与视觉系统，阶段十二完成产品首页、旅行需求向导、订单资料中心和
规划进度页。阶段十三完成系统核心结果页，包括方案比较、地图与时间线联动、
预算分析、冲突与证据、不可变版本重规划和费用操作人工确认。阶段十四完成
行程工作台、偏好与记忆、反馈中心、受控开发者追踪、系统状态以及发布验收链路。
阶段十五已建立功能、契约、并发、故障、性能、安全、可访问性和覆盖率测试
控制面。测试阶段十已完成智能能力的三轮 Mock 基线采集；测试阶段十一已完成
Chromium/WebKit 前端稳定性与可访问性矩阵，Firefox 因当前 macOS / TRAE 沙箱
profile 目录限制保持环境阻塞。测试阶段十二已完成 OpenTelemetry、日志、指标和
告警的离线审计与模拟故障验证，真实 Prometheus / Alertmanager 小流量故障注入
仍未执行。测试阶段十三已执行最终全量回归，结论为 No-Go：依赖扫描存在
Critical/High 漏洞，前后端 E2E 存在过期会话 return path 失败，性能、负载、
镜像扫描、故障恢复和最终数据一致性在当前环境仍未完整验收。真实模型、真实 OCR、
人工语义复核、真实后端小流量、真实可观测性告警和质量阈值仍未验收，不能将 Mock
或模拟基线解释为生产质量结论。

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
docs                                中文架构、领域、状态机和阶段验收文档
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

阶段十一前端稳定性矩阵使用 Mock 路由隔离数据，覆盖 Chromium、Firefox、WebKit
与 375x667 至 1920x1080 视口，并生成
`output/test-results/<QA_RUN_ID>/json/stage11-summary.json`。当前本机执行中
Chromium/WebKit 已通过，Firefox 在浏览器启动阶段被沙箱/profile 目录问题阻塞；
详情见[测试阶段十一验收报告](docs/测试阶段十一-前端稳定性与可访问性验收.md)。
该结果不替代真实后端小流量报告。

阶段十二可观测性与告警验收使用离线证据审计和模拟阈值验证，覆盖 Trace 组件、
HTTP/Kafka/log 关联、指标源、Prometheus 告警规则和隐私脱敏扫描，并生成
`output/test-results/<QA_RUN_ID>/json/stage12-summary.json`。当前离线审计和 9 类
模拟故障告警均通过，完整门禁因真实 Prometheus / Alertmanager 小流量故障注入未执行
保持 `BLOCKED_FOR_REAL_VALIDATION`；详情见
[测试阶段十二验收报告](docs/测试阶段十二-可观测性与告警验收.md)。该结果不替代
真实告警触发、恢复和日志/指标/Trace 互相关联验证。

测试阶段十三最终全量回归按 Secret Scan、SAST、依赖/镜像、单元、契约、集成、
E2E、AI、安全、性能、负载、故障恢复和数据一致性顺序执行，主证据位于
`output/test-results/stage13-20260916T100316Z/`，AI 子证据位于
`output/test-results/stage13-20260916T100316Z-ai-regression/` 和
`output/test-results/stage13-20260916T100316Z-ai-security/`。当前结论为 No-Go；
详情见[测试阶段十三最终全量回归验收报告](docs/测试阶段十三-最终全量回归验收.md)。

统一阈值位于 `qa/config/thresholds.json`，合成数据清单位于
`qa/fixtures/manifest.json`。JUnit XML、JSON、HTML、覆盖率、性能结果和脱敏日志
统一保存到 `output/test-results/<QA_RUN_ID>/`。测试环境审计、实测结果和未验收项
见[测试阶段一：可测试性审计与统一测试环境验收](docs/测试阶段一-可测试性审计与统一测试环境验收.md)；
完整自动化体系的历史验收见
[阶段十五自动化测试与稳定性验收](docs/阶段十五-自动化测试与稳定性验收.md)。

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

阶段十 E2E 覆盖以下真实后端路径：

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
- [阶段四：订单解析](docs/阶段四-订单文档解析.md)
- [阶段五：双层检索与 GraphRAG](docs/阶段五-双层检索.md)
- [阶段六：MCP 与 A2A](docs/阶段六-工具协议与智能体协作.md)
- [阶段七：端到端规划](docs/阶段七-端到端旅行规划.md)
- [阶段八：异步治理](docs/阶段八-异步治理.md)
- [阶段九：可观测性与评测](docs/阶段九-可观测性安全防护与评测.md)
- [阶段十：后端集成验收](docs/阶段十-后端验收.md)
- [阶段十一：前端工程与视觉系统](docs/阶段十一-前端工程与视觉系统.md)
- [阶段十二：旅行创建主流程](docs/阶段十二-旅行创建主流程.md)
- [阶段十三：系统核心结果页](docs/阶段十三-系统核心结果页.md)
- [阶段十四：辅助页面、全量联调与发布验收](docs/阶段十四-辅助页面全量联调与发布验收.md)
- [阶段十五：自动化测试与稳定性验收](docs/阶段十五-自动化测试与稳定性验收.md)
- [测试阶段一：可测试性审计与统一测试环境验收](docs/测试阶段一-可测试性审计与统一测试环境验收.md)
- [测试阶段十：智能能力正确性、鲁棒性与可重复性验收](docs/测试阶段十-智能能力评测验收.md)
- [测试阶段十一：前端稳定性与可访问性验收](docs/测试阶段十一-前端稳定性与可访问性验收.md)
- [测试阶段十二：可观测性与告警验收](docs/测试阶段十二-可观测性与告警验收.md)
- [测试阶段十三：最终全量回归验收](docs/测试阶段十三-最终全量回归验收.md)

## 阶段边界

阶段十五已建立自动化测试与 CI 门禁，但 Docker 依赖的性能、长稳、故障注入
和动态安全套件尚未在当前环境完成，前端覆盖率也未达到长期目标。阶段十三最终
全量回归当前为 No-Go，必须先清零 Critical/High 依赖漏洞、修复 E2E return path
缺陷，并在具备 Docker、k6 和授权数据库的环境中完成性能、负载、故障恢复和一致性
复验。阶段十二的可观测性结论目前仅覆盖离线审计和模拟告警，不能替代真实 Prometheus /
Alertmanager 小流量故障注入。费用确认仅记录候选授权，不会自动支付或调用真实
供应商；演示数据不得解释为真实第三方调用成功。智能能力 Mock 基线同样不得替代
真实模型、真实图片和人工语义验收。
