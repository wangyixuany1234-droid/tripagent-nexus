# 测试阶段三：OpenAPI 与多协议契约一致性验收

## 1. 验收结论

执行日期：2026-09-09  
契约运行标识：`20260909T-stage3`  
回归运行标识：`20260909T-stage3-regression`

总体结论：**通过**。

阶段三正式命令退出码为 0。冻结 OpenAPI、当前 OpenAPI、FastAPI 内部 OpenAPI
和 AsyncAPI 均通过语法校验；Spring 运行时 OpenAPI、Controller 映射、FastAPI
运行时路由、TypeScript SDK、Kafka、MCP 和 A2A Schema 均完成一致性验证。

Schemathesis 共覆盖 44 个操作并生成 4440 个请求，全部通过，未发现 HTTP 5xx
或进程崩溃。完整后端回归也已重新执行并以退出码 0 结束。

## 2. 测试范围

### 2.1 OpenAPI 静态与运行时一致性

- 使用 Redocly 校验冻结、当前和 FastAPI 内部 OpenAPI；
- 启动 Spring Boot 后抓取 `/v3/api-docs`；
- 比较 Spring Controller 的 HTTP method/path 与当前契约；
- 比较运行时 OpenAPI、当前契约和冻结契约；
- 校验响应状态码、Content-Type、成功响应和错误响应 Schema；
- 验证必填字段、枚举、格式、最小值、最大值和
  `additionalProperties: false`；
- 验证格式错误的 JSON、multipart、UUID、日期、布尔值、枚举和 Header
  不会触发 500。

### 2.2 破坏性变更检测

兼容性检查覆盖：

- 删除 path 或 operation；
- 删除参数或请求/响应媒体类型；
- 字段删除或改名；
- 类型变化；
- 新增必填字段；
- enum 值收窄；
- min/max 范围收窄；
- `anyOf`/`oneOf` 中保留或移除基线类型。

当前候选契约相对冻结契约的破坏性变更数为 0。回归测试包含 5 个测试方法和
8 个 mutation 子用例，均通过。

### 2.3 TypeScript SDK

- 从 `packages/api-contract/openapi.yaml` 临时重新生成
  `typescript-fetch` SDK；
- 编译 CommonJS 配置；
- 编译 ESM 配置；
- 将生成结果与仓库中的 `apps/web/src/api/generated/src` 逐文件比较。

结果：两种模块格式编译成功，未检测到 SDK 漂移。

### 2.4 Kafka、MCP 与 A2A

- Kafka Event Envelope 使用 JSON Schema Draft 2020-12 校验；
- MCP Tool Input/Output 使用封闭对象和边界约束校验；
- A2A Message 校验任务、状态、上下文和隐私最小化字段；
- 旧消费者忽略新增可选字段；
- 未知事件版本在首次消费时进入死信隔离；
- 未知版本错误码为 `EVENT_VERSION_UNSUPPORTED`，且
  `retryable=false`，不会形成持续重试循环。

### 2.5 动态生成请求

公共 Spring API 与 FastAPI 内部 API 均执行 Schemathesis coverage 和 fuzzing
阶段，固定 `QA_RANDOM_SEED=20260908`，单 worker 执行，默认使用 Mock
Model Gateway、Mock OCR 和 Mock MCP，不调用真实第三方服务。

SSE 接口保持 `text/event-stream` 原始流语义。其动态检查覆盖状态码、
Content-Type、必填 Header、非法输入和无 5xx；事件载荷通过
`x-sse-event-schema` 和静态协议测试校验，而不是将整条流错误地当作单个 JSON
对象解析。

## 3. 实现与文件变更

### 3.1 契约与协议

- `contracts/openapi/tripagent-v1.yaml`
- `packages/api-contract/openapi.yaml`
- `packages/api-contract/ai-internal-openapi.yaml`
- `packages/api-contract/asyncapi.yaml`
- `packages/api-contract/events/kafka-event.schema.json`
- `packages/api-contract/mcp-tools.schema.json`
- `packages/api-contract/a2a-message.schema.json`

### 3.2 Spring Boot

- `apps/trip-agent-api/pom.xml`
- `apps/trip-agent-api/src/main/java/com/tripagent/nexus/common/error/GlobalExceptionHandler.java`
- `apps/trip-agent-api/src/main/java/com/tripagent/nexus/infrastructure/config/JacksonConfiguration.java`
- `apps/trip-agent-api/src/main/java/com/tripagent/nexus/integration/messaging/EventEnvelope.java`
- `apps/trip-agent-api/src/main/java/com/tripagent/nexus/integration/document/DocumentParseEvents.java`
- `apps/trip-agent-api/src/main/java/com/tripagent/nexus/integration/planning/PlanningEvents.java`
- `apps/trip-agent-api/src/test/java/com/tripagent/nexus/contract/RuntimeOpenApiContractTest.java`
- `apps/trip-agent-api/src/test/java/com/tripagent/nexus/contract/ProtocolJsonSchemaContractTest.java`
- `apps/trip-agent-api/src/test/java/com/tripagent/nexus/integration/messaging/AsyncResilienceTest.java`

为保持严格类型校验后的回归一致性，还修正了 5 个 Java 集成测试文件中的金额
fixture，使合法请求发送 JSON number，并增加字符串金额被拒绝的断言。

### 3.3 FastAPI

- `services/ai-retrieval-service/src/trip_agent_ai/core/errors.py`
- `services/ai-retrieval-service/src/trip_agent_ai/api/documents.py`
- `services/ai-retrieval-service/src/trip_agent_ai/api/retrieval.py`
- `services/ai-retrieval-service/src/trip_agent_ai/schemas/retrieval.py`
- `services/ai-retrieval-service/tests/test_openapi_runtime_contract.py`
- `services/ai-retrieval-service/tests/test_protocol_contracts.py`
- `services/ai-retrieval-service/pyproject.toml`
- `services/ai-retrieval-service/uv.lock`

`jsonschema==4.25.1` 已作为开发依赖显式锁定，避免测试依赖本机偶然安装状态。

### 3.4 测试控制面

- `qa/scripts/check_openapi_compatibility.py`
- `qa/scripts/check_sdk_drift.sh`
- `qa/scripts/run_contract.sh`
- `qa/scripts/run_contract_stage3.sh`
- `qa/scripts/test_environment.sh`
- `qa/tests/test_openapi_compatibility.py`
- `qa/tests/test_runtime_http_contract.py`
- `.github/workflows/quality-gates.yml`
- `Makefile`
- `docs/错误码.md`

## 4. 实际执行命令

阶段三正式门禁：

```bash
QA_RUN_ID=20260909T-stage3 \
QA_RANDOM_SEED=20260908 \
make test-contract-stage3
```

完整后端回归：

```bash
QA_RUN_ID=20260909T-stage3-regression \
QA_RANDOM_SEED=20260908 \
make test-functional-backend
```

附加校验：

```bash
mvn -B -ntp spotless:check
python3 -m unittest qa.tests.test_qa_harness
uv lock --check
docker build -t tripagent-stage3-ai-lock-check:local \
  -f services/ai-retrieval-service/Dockerfile \
  services/ai-retrieval-service
```

所有命令均在本地测试环境执行，`REAL_MODEL_TEST_ENABLED=false`。

## 5. 测试结果

### 5.1 契约门禁

| 范围 | 结果 |
| --- | --- |
| 阶段三正式命令 | 退出码 0 |
| 契约子套件 | 14/14 通过 |
| Java 契约测试 | 16/16 通过 |
| FastAPI 静态契约测试 | 7/7 通过 |
| 运行时 HTTP 边界测试 | 2/2 通过 |
| 兼容性回归 | 5/5 测试、8/8 子用例通过 |
| 冻结/当前/内部 OpenAPI | 全部有效 |
| AsyncAPI | 有效，0 error、0 warning、1 information |
| TypeScript SDK | CommonJS/ESM 编译通过，无漂移 |
| Kafka/MCP/A2A Schema | 3/3 通过 |
| 破坏性变更 | 0 |

### 5.2 Schemathesis

| 范围 | 操作数 | 生成请求 | 通过 | 失败 | HTTP 5xx |
| --- | ---: | ---: | ---: | ---: | ---: |
| Spring 公共 API（非 SSE） | 36 | 3148 | 3148 | 0 | 0 |
| Spring SSE | 1 | 60 | 60 | 0 | 0 |
| FastAPI 内部 API | 7 | 1232 | 1232 | 0 | 0 |
| 合计 | 44 | 4440 | 4440 | 0 | 0 |

### 5.3 完整后端回归

| 范围 | 通过 | 失败 | 跳过 | 结论 |
| --- | ---: | ---: | ---: | --- |
| Java 全量 | 152 | 0 | 1 | 通过 |
| PostgreSQL Testcontainers | 4 | 0 | 0 | 通过 |
| Python 全量 | 64 | 0 | 0 | 通过 |
| QA harness | 10 | 0 | 0 | 通过 |
| Java Spotless | 1 | 0 | 0 | 通过 |
| Python Ruff lint/format | 2 | 0 | 0 | 通过 |

唯一跳过项为真实模型冒烟测试，符合默认门禁要求。

## 6. 覆盖率

| 分层 | 实际行覆盖率 | 最低要求 | 结果 |
| --- | ---: | ---: | --- |
| Java 核心 domain 和规则引擎 | 97.11% | 85% | 通过 |
| Java application service | 84.13% | 80% | 通过 |
| Java Controller | 85.71% | 75% | 通过 |
| FastAPI route | 97.53% | 75% | 通过 |

整体覆盖率：

| 运行时 | 行/语句覆盖率 | 分支覆盖率 |
| --- | ---: | ---: |
| Java | 82.63% | 60.44% |
| Python | 83.41% | 69.72% |

## 7. 失败案例与修复

最终正式门禁和完整回归失败数均为 0。实施过程中发现并修复：

| 问题 | 修复与验证 |
| --- | --- |
| 新增的 405/415 错误码未进入文档门禁 | 在 `docs/错误码.md` 补充 `METHOD_NOT_ALLOWED` 和 `CONTENT_TYPE_UNSUPPORTED` |
| FastAPI 非法 JSON 返回默认响应 | 增加 Starlette HTTP 异常处理并统一输出 ProblemDetails |
| FastAPI 接受数字日期、数字布尔值和缺失图字段 | 使用前置校验、`StrictBool` 和必填列表收紧模型 |
| 旧事件版本可能持续失败 | 未知版本首次消费即进入隔离，标记为不可重试 |
| LanceDB bind mount 被运行中容器持有后删除 | 调整测试环境顺序，先销毁 Compose 再删除运行目录 |
| SSE 被建模为对象会破坏流式 SDK | 保留字符串流响应，以扩展字段关联事件 Schema，并独立执行动态检查 |
| Java 旧 fixture 将契约中的 number 写成字符串 | 改用 `BigDecimal` JSON number，并新增字符串金额拒绝用例 |
| Python 测试隐式依赖 `jsonschema` | 在 dev extra 中固定版本并更新 `uv.lock`；冻结依赖 Docker 构建通过 |

## 8. 执行耗时与性能边界

正式契约门禁记录的子套件耗时合计为 151.496 秒，不包含 Compose 镜像构建、
健康等待和销毁时间。主要动态套件耗时：

| 套件 | 耗时 |
| --- | ---: |
| Spring 公共 API（非 SSE） | 30.950 秒 |
| Spring SSE | 3.149 秒 |
| FastAPI 内部 API | 16.055 秒 |
| TypeScript SDK 漂移与双模块编译 | 11.218 秒 |

最终统一后端回归的子步骤耗时合计约 218.338 秒。本阶段是契约与功能回归，不是
吞吐量、延迟或长稳性能验收，因此不据此声明性能指标达标。

## 9. 已知提示与剩余风险

- 公共 Schemathesis 对需要已有资源的 14 个操作主要命中 404，1 个操作主要命中
  401；这些响应均符合契约，但生成式测试未替代有状态业务流程测试；
- Schemathesis 对部分操作提示实现校验比 Schema 更严格。已通过 Java/Python
  功能测试覆盖成功路径，后续可引入有状态 fixture hooks 以提高动态正向深度；
- SSE 流未作为单个 JSON 文档执行响应体 Schema 校验，事件结构由独立协议
  Schema 测试覆盖；
- AsyncAPI 2.6 校验通过，但工具提示可迁移到 3.1；
- Python 测试有 2 条 Starlette/httpx 弃用提示，不影响本次结果；
- 真实模型测试未启用，本报告不评价真实模型或第三方服务稳定性。

## 10. 验收标准

| 验收项 | 结果 |
| --- | --- |
| OpenAPI 无语法错误 | 通过 |
| Spring/FastAPI 运行时响应符合契约 | 通过 |
| 前端 SDK 可生成并编译 | 通过 |
| Kafka、MCP、A2A 消息通过 Schema 校验 | 通过 |
| 破坏性变更导致门禁失败 | 通过 |
| 未知事件版本进入隔离且不持续重试 | 通过 |
| 自动生成非法请求不产生 500 或进程崩溃 | 通过 |
| 测试 Profile 隔离生产资源并禁用真实模型 | 通过 |
| 完整 Java/Python 回归与覆盖率门禁 | 通过 |

## 11. 报告与证据

- 机器汇总：
  `output/test-results/20260909T-stage3/json/stage3-summary.json`
- 契约结果：
  `output/test-results/20260909T-stage3`
- 后端回归：
  `output/test-results/20260909T-stage3-regression`
- Java 覆盖率 HTML：
  `output/test-results/20260909T-stage3-regression/coverage/java/index.html`
- Python 覆盖率 HTML：
  `output/test-results/20260909T-stage3-regression/coverage/python/html/index.html`

