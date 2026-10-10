# 测试阶段二：Spring Boot 与 FastAPI 完整功能测试验收

## 1. 验收结论

执行日期：2026-09-09  
运行标识：`20260908T-stage2`

总体结论：**通过**。

Spring Boot 与 FastAPI 功能测试、PostgreSQL Testcontainers 测试、OpenAPI
契约检查、错误响应信封校验、TypeScript SDK 漂移检查和分层行覆盖率门禁均已
实际执行并通过。

最终结果：

- Java 全量测试 147 个，146 个通过，0 个失败，1 个按门禁跳过；
- PostgreSQL 17.6 Testcontainers 功能测试 4 个，全部通过；
- Python 测试 59 个，全部通过；
- OpenAPI、Controller 和错误码契约测试 8 个，全部通过；
- QA harness 回归测试 10 个，全部通过；
- 四项分层行覆盖率均高于阶段门槛；
- 最终统一后端测试入口退出码为 0。

唯一跳过项为真实模型冒烟测试。`REAL_MODEL_TEST_ENABLED` 未启用，符合默认禁止
调用真实模型的安全约束。本阶段按要求未执行前端测试、负载测试、长稳测试或真实
模型测试。

## 2. 测试范围

### 2.1 旅行需求

覆盖 `POST /api/v1/trips`、`GET /api/v1/trips/{tripId}` 和
`PUT /api/v1/trips/{tripId}/requirements`：

- 正常创建、读取和修改；
- 必填字段缺失、未知字段和超长自然语言约束；
- 开始日期晚于结束日期；
- 零预算、负预算、超大预算和金额小数精度；
- 不支持的币种；
- UTC 持久化与用户时区转换；
- 使用 `X-User-Id` 验证资源归属；
- 用户 A 读取用户 B 的旅行时返回相同的未找到语义，避免资源枚举。

### 2.2 文档解析

覆盖文档上传、读取和订单人工修正：

- 合法 PNG 和 PDF；
- 相同内容重复上传；
- MIME 与文件签名不一致；
- 空文件和超过限制的文件；
- OCR 低置信度、缺少日期和缺少金额；
- 同一提取结果中的重复确认号；
- 人工修正后的原值、新值、操作者、原因和 UTC 时间审计。

### 2.3 行程生成

覆盖方案生成、查询、重规划和确认：

- 正常生成两个方案；
- 到达城市前安排当地活动、景点闭馆、通勤不足和日程重叠；
- 总预算超限和推荐证据缺失；
- 规则引擎自动修复；
- 锁定行程项后重规划；
- 重规划生成新版本并保留旧版本；
- 相同幂等键重复确认返回相同结果；
- 不同幂等键再次确认返回冲突；
- 未确认方案不能执行有成本动作。

### 2.4 异步任务

覆盖任务查询、SSE 事件和取消：

- 正常状态流转与非法状态跳转；
- 运行中任务取消、已完成任务取消和任务不存在；
- SSE 重连及 `Last-Event-ID` 补发；
- Kafka inbox 重复事件去重；
- 事件时间戳乱序时仍按数据库 sequence 回放。

### 2.5 反馈和用户记忆

- 正常反馈和幂等重复反馈；
- 删除自己的用户记忆；
- 用户 A 不能读取或删除用户 B 的记忆；
- 模型推断偏好仅作为证据；
- 未经用户确认的推断偏好不会成为强约束；
- 用户显式加入后仅按软约束参与规划。

### 2.6 契约和数据语义

- 被测成功响应使用 OpenAPI Schema 校验；
- 被测错误响应统一校验 `code`、`message`、`traceId` 和 `timestamp`；
- Java 使用 `BigDecimal`、Python 使用 `Decimal` 验证金额精度；
- 时间统一验证为 UTC，并覆盖东京时区跨日期边界；
- OpenAPI 与 Spring Controller method/path 一致；
- 生成的 TypeScript SDK 与 OpenAPI 无漂移，但未执行前端测试。

## 3. 实现与文件变更

### 3.1 Spring Boot 业务与接口

- `apps/trip-agent-api/src/main/resources/application.yml`
- `apps/trip-agent-api/src/main/java/com/tripagent/nexus/application/service/TripApplicationService.java`
- `apps/trip-agent-api/src/main/java/com/tripagent/nexus/controller/TripController.java`
- `apps/trip-agent-api/src/main/java/com/tripagent/nexus/controller/UserPreferenceController.java`
- `apps/trip-agent-api/src/main/java/com/tripagent/nexus/domain/model/Trip.java`
- `apps/trip-agent-api/src/main/java/com/tripagent/nexus/domain/model/Feedback.java`
- `apps/trip-agent-api/src/main/java/com/tripagent/nexus/domain/model/Place.java`
- `apps/trip-agent-api/src/main/java/com/tripagent/nexus/domain/model/TravelPlan.java`
- `apps/trip-agent-api/src/main/java/com/tripagent/nexus/domain/model/Evidence.java`
- `apps/trip-agent-api/src/main/java/com/tripagent/nexus/domain/model/ParsedBooking.java`

### 3.2 Java 测试

- `apps/trip-agent-api/src/test/java/com/tripagent/nexus/testsupport/OpenApiSchemaAssertions.java`
- `apps/trip-agent-api/src/test/java/com/tripagent/nexus/integration/Phase2ApiFunctionalTestcontainersIntegrationTest.java`
- `apps/trip-agent-api/src/test/java/com/tripagent/nexus/integration/EndToEndPlanningIntegrationTest.java`
- `apps/trip-agent-api/src/test/java/com/tripagent/nexus/integration/DocumentParsingIntegrationTest.java`
- `apps/trip-agent-api/src/test/java/com/tripagent/nexus/integration/AsyncGovernanceIntegrationTest.java`
- `apps/trip-agent-api/src/test/java/com/tripagent/nexus/application/document/BookingRuleValidatorTest.java`
- `apps/trip-agent-api/src/test/java/com/tripagent/nexus/application/planning/PlanningContextFactoryTest.java`

### 3.3 FastAPI 与 Python 测试

- `services/ai-retrieval-service/src/trip_agent_ai/schemas/problem.py`
- `services/ai-retrieval-service/src/trip_agent_ai/core/errors.py`
- `services/ai-retrieval-service/tests/openapi_assertions.py`
- `services/ai-retrieval-service/tests/test_phase2_functional_api.py`

### 3.4 契约、SDK 和测试控制面

- `packages/api-contract/openapi.yaml`
- `packages/api-contract/ai-internal-openapi.yaml`
- `apps/web/src/api/generated/src/apis/TripsApi.ts`
- `apps/web/src/api/generated/src/apis/PreferencesApi.ts`
- `docs/错误码.md`
- `qa/config/thresholds.json`
- `qa/scripts/check_backend_functional_coverage.py`
- `qa/scripts/run_backend_functional.sh`
- `qa/tests/test_qa_harness.py`
- `Makefile`

`contracts/openapi/tripagent-v1.yaml` 继续作为冻结兼容性基线，不因新增可选 Header
改变历史基线。

## 4. 实际执行命令

完整后端功能流水线：

```bash
QA_RUN_ID=20260908T-stage2 make test-functional-backend
```

该入口实际依次执行：

```bash
mvn -B -ntp -pl apps/trip-agent-api clean test
mvn -B -ntp -pl apps/trip-agent-api jacoco:report@report
mvn -B -ntp -Ptestcontainers verify \
  -Dit.test=Phase2ApiFunctionalTestcontainersIntegrationTest \
  -Djacoco.skip=true
python -m ruff check
python -m ruff format --check
python -m pytest --cov=trip_agent_ai --cov-branch
python3 qa/scripts/check_backend_functional_coverage.py
```

契约 SDK 同步与漂移检查：

```bash
./scripts/generate-typescript-sdk.sh
./qa/scripts/check_sdk_drift.sh
```

覆盖率路径匹配修复后的收口验证：

```bash
python3 -m unittest qa.tests.test_qa_harness
python -m ruff check \
  qa/scripts/check_backend_functional_coverage.py \
  qa/tests/test_qa_harness.py
python -m ruff format --check \
  qa/scripts/check_backend_functional_coverage.py \
  qa/tests/test_qa_harness.py
```

## 5. 测试结果

| 范围 | 通过 | 失败 | 跳过 | 结论 |
| --- | ---: | ---: | ---: | --- |
| Java 全量测试 | 146 | 0 | 1 | 通过 |
| 阶段二 PostgreSQL Testcontainers | 4 | 0 | 0 | 通过 |
| Python 全量测试 | 59 | 0 | 0 | 通过 |
| Java 契约测试（包含于 Java 全量） | 8 | 0 | 0 | 通过 |
| QA harness 回归 | 10 | 0 | 0 | 通过 |
| TypeScript SDK 漂移检查 | 1 | 0 | 0 | 通过 |
| Ruff lint / format | 2 | 0 | 0 | 通过 |

PostgreSQL Testcontainers 使用 `postgres:17.6-alpine`。测试 Profile、Mock Model
Gateway、Mock OCR 和 Mock MCP 保持启用，未向第三方模型、地图、天气或路线服务
发送测试流量。

## 6. 覆盖率

### 6.1 分层门禁

| 分层 | 实际行覆盖率 | 最低要求 | 结果 |
| --- | ---: | ---: | --- |
| Java 核心 domain 和规则引擎 | 97.11% | 85% | 通过 |
| Java application service | 84.34% | 80% | 通过 |
| Java Controller | 90.27% | 75% | 通过 |
| FastAPI route | 97.47% | 75% | 通过 |

Controller 统计只包含 `controller/` 直属 Java 文件，不包含
`controller/dto/ApiModels.java`。覆盖率匹配器已改为按路径段匹配，并增加回归测试
防止 `*` 再次跨目录。

### 6.2 整体覆盖率

| 运行时 | 行覆盖率 | 分支覆盖率 |
| --- | ---: | ---: |
| Java | 82.8541% | 60.6372% |
| Python | 81.2360% | 70.1149% |

本阶段以业务分层行覆盖率为验收门槛，没有为了提高数字增加无意义 getter 测试。

## 7. 失败案例与修复

最终流水线失败数为 0。实施期间发现并修复：

| 问题 | 修复与验证 |
| --- | --- |
| 请求未知字段被 Spring 静默接受 | 启用严格反序列化，返回 `REQUEST_INVALID` |
| 零预算和不支持币种缺少领域错误 | 新增明确业务校验及错误码 |
| 旅行和偏好读取缺少调用方作用域 | 增加可选 `X-User-Id` 归属校验 |
| Java 可选响应字段输出 `null` 违反契约 | 对非 nullable 可选字段使用 `NON_NULL` |
| FastAPI 错误信封缺少字段 | 补齐 `message`、`traceId` 和 UTC `timestamp` |
| SSE 未知 `Last-Event-ID` 场景返回非预期响应 | 测试同时接受 SSE 和 JSON 错误媒体类型 |
| NetworkNT 处理 OpenAPI `allOf` 出现兼容误判 | 测试侧规范化 Schema 后再验证 |
| Python 3.9 不支持 `datetime.UTC` | 改用 `timezone.utc` |
| JaCoCo 报告未进入阶段目录 | 调用命名 execution `jacoco:report@report` |
| Controller 覆盖率误包含 DTO | 改为路径段匹配并增加 QA 回归测试 |

## 8. 执行耗时与性能边界

本阶段是功能正确性阶段，不是性能验收阶段，因此未采集接口吞吐量、p95、p99、
资源增长或长稳指标，也不据此声明性能达标。

已记录的测试执行耗时：

| 步骤 | 耗时 |
| --- | ---: |
| Java 全量功能测试 | 141.514 秒 |
| Java JaCoCo 报告 | 4.999 秒 |
| PostgreSQL Testcontainers | 34.878 秒 |
| Python Ruff lint | 0.932 秒 |
| Python Ruff format | 0.181 秒 |
| Python 全量功能测试 | 30.942 秒 |
| 分层覆盖率门禁 | 0.487 秒 |
| TypeScript SDK 漂移检查 | 14.913 秒 |

统一后端入口中已记录步骤耗时合计约 212.93 秒；该值是步骤汇总，不等同于服务
性能指标。

## 9. 发现的问题与剩余风险

- Python 测试仍有 2 条 Starlette/httpx 测试客户端弃用警告，不影响本次结果，
  后续依赖升级时应消除；
- Java Schema Validator 会对部分 OpenAPI 根关键字输出非阻断提示，Schema
  断言本身通过；
- 真实模型测试按门禁跳过，不能据此评价真实模型质量或第三方服务稳定性；
- 本阶段未执行前端、负载、长稳、故障注入和动态安全扫描，相关结论应由对应阶段
  的独立报告给出；
- TypeScript SDK 已同步且无漂移，但本阶段没有执行前端行为回归。

## 10. 验收标准

| 验收项 | 结果 |
| --- | --- |
| Spring Boot 正常、边界和异常功能测试 | 通过 |
| FastAPI 异步功能测试 | 通过 |
| Java 使用 JUnit 5、RestAssured、Testcontainers | 通过 |
| Python 使用 pytest、pytest-asyncio | 通过 |
| 错误响应四字段校验 | 通过 |
| 成功响应 OpenAPI Schema 校验 | 通过 |
| 金额精度校验 | 通过 |
| UTC 和用户时区转换 | 通过 |
| 核心 domain 和规则引擎行覆盖率不低于 85% | 通过，97.11% |
| application service 行覆盖率不低于 80% | 通过，84.34% |
| Controller 行覆盖率不低于 75% | 通过，90.27% |
| FastAPI route 行覆盖率不低于 75% | 通过，97.47% |
| 默认不调用真实模型和第三方服务 | 通过 |
| 不执行前端测试 | 符合阶段要求 |

## 11. 报告产物

结果根目录：

```text
output/test-results/20260908T-stage2
```

关键产物：

- `output/test-results/20260908T-stage2/json/stage2-summary.json`
- `output/test-results/20260908T-stage2/json/backend-functional-coverage.json`
- `output/test-results/20260908T-stage2/coverage/java/jacoco.xml`
- `output/test-results/20260908T-stage2/coverage/java/index.html`
- `output/test-results/20260908T-stage2/coverage/python/coverage.json`
- `output/test-results/20260908T-stage2/coverage/python/html/index.html`
- `output/test-results/20260908T-stage2/junit/java/`
- `output/test-results/20260908T-stage2/junit/testcontainers/`
- `output/test-results/20260908T-stage2/junit/python/pytest.xml`
- `output/test-results/20260908T-stage2/logs/`

所有 fixture 使用合成数据；报告未写入真实 API Key、真实订单或用户隐私数据。
