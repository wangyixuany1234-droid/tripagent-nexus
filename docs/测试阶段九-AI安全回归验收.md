# 测试阶段九：AI 安全回归验收

## 结论

**当前可执行回归 182/182 通过，完整阶段门禁仍为 `BLOCKED`。** 本阶段与早期开发文档
`阶段九-可观测性安全防护与评测.md` 的阶段编号不同，不沿用其 12/12 基线结论。

首次验收新增测试、合成语料、执行入口和报告；随后依次收紧 A2A/MCP 安全边界、
Prompt 检测和 RAG 长文本处理。失败用例保留普通失败断言，没有标记为预期失败或
降低校验要求。当前阻断原因仅为 7 个需要真实模型、真实 OCR、外部来源/网络、
远程 Agent、真实费用供应商或隔离 Jaeger 环境的未执行项。阶段八的可信 JWT
身份与统一资源授权已修复并通过真实 HTTP 复验；S8-004、依赖、最终镜像、TLS
入口和 Git 历史等发布阻断项仍然有效。

最新完整门禁复验证据目录：
`output/test-results/20260916T-stage9-auth-final/`。

- 执行命令：
  `make test-ai-security QA_RUN_ID=20260916T-stage9-auth-final`。
- 机器汇总为 `json/stage9-summary.json`，完整阶段门禁为
  `json/stage9-release.xml`；门禁按设计以非零状态结束，不代表测试用例失败。
- Java 100/100、Python 63/63、前端组件 7/7、Playwright 4/4、执行器
  8/8 通过；前端生产构建通过且不计入用例数。
- 6 个批次均为 `PASSED`，合计耗时约 62.038 秒；0 失败、0 错误、0 跳过，
  `missingOrChangedCoverage=[]`，`gapCount=7`。
- 当前源指纹为
  `b3c32bb5f2fbfbb9bfd9e5bd03ddf41308a3c8267462680b861fcbd4eb50b3b9`。

首次验收历史证据目录：
`output/test-results/20260915T-stage9-ai-security-acceptance/`。
初始、verified、final 三个调试批次另行保留，不与验收批次累加。

### A2A 与 MCP 资源授权修复复验

按 S9-07、S9-09、S9-10 的建议顺序完成定向修复，复验证据目录为：
`output/test-results/20260915T-stage9-a2a-mcp-remediation/`。

- A2A 安全测试 11/11 通过：SDK Task ID、contextId、结果 taskId、目标 Agent、
  具体输出类型和 1 MiB 上限均在接收端校验；未认证任务 owner 和 Card/allowlist
  不一致均被拒绝。
- MCP 资源授权相关测试 5/5 通过：`booking_parse` 与 `itinerary_store` 在读取或
  写入前，根据可信 owner task、运行中的 Agent 执行记录和
  Task→Plan→Trip 关系校验资源归属；缺失、跨行程或非规划任务统一按资源不可用拒绝。
- 聚焦 A2A/MCP、编排、混沌与错误码回归 30/30 通过。
- A2A 与 MCP 契约已补充编排层生成的 owner task、响应关联和资源型工具约束。
- 阶段九总计更新为 **162 项：88 通过、74 失败、0 错误、0 跳过**。
  完整门禁仍为 `BLOCKED`，原因是其他安全问题和 G01-G08 未验收范围尚未处理。
- MCP 服务身份变化、工具名碰撞、非法返回 Schema 与工具内容注入仍有 4 个失败断言，
  不属于本次“资源授权”修复结论。

### Prompt 与 RAG 长文本修复复验

按 S9-01、S9-02、S9-03、S9-05 的顺序完成修复，复验证据目录为：
`output/test-results/20260915T-stage9-prompt-remediation/`。

- Java 与 Python 检测器统一执行 NFKC 规范化、零宽格式字符移除，以及中英文的指令
  覆盖、敏感数据外泄、规则绕过、成本动作绕过、未授权工具、跨用户读取、外部上下文
  外泄和伪造证据检测。
- Java Prompt 安全测试 35/35 通过；Python 直接注入 17/17、正常对照 3/3、攻略/
  记忆/标题/长文尾部间接注入 24/24、OCR 注入 6/6 均通过。
- RAG 在任何摘要截断前检查完整标题与正文；不可信前缀计入 1000 字符上限，
  合法长文不再触发 Evidence 长度校验 500。
- 拒绝事件仅记录稳定错误码、来源和规则信号，不记录攻击全文；Java/Python 安全事件
  断言均通过。
- 阶段九总计更新为 **162 项：149 通过、13 失败、0 错误、0 跳过**，无缺失或
  变更覆盖，源指纹为
  `7a4e0552b7181d77a3edc03fdc7cf630ea01deedbe8bd0e8cc2accdca54e50f3`。
- 完整门禁仍为 `BLOCKED`：跨城市 chunkId 覆盖、MCP 非资源边界、模型输出、
  确定性规则和 G01-G08 尚未完成。

### 剩余确定性问题修复复验

S9-04、S9-06、S9-08、S9-11、S9-12 已完成修复，最终复验证据目录为：
`output/test-results/20260915T-stage9-remediation-final/`。

- LanceDB 公共知识使用 `city + chunkId` 复合 merge key，跨城市同名 chunk 不再覆盖。
- MCP 固定 `serverInfo` 名称和版本，拒绝重复工具名及 manifest 漂移，并在客户端边界
  根据本地输出 Schema 校验 `structuredContent`；工具内容注入降级为不可信结果。
- 模型输出只接受一个 RFC 8259 JSON 文档，拒绝重复键和尾随文档；一次修复保留原
  system policy，并将原输出标记为不可信数据；计划金额增加非负约束。
- 规则引擎独立复核负金额、预算明细/汇总一致性、倒置时间和 evidenceId 归属，
  不采信模型“已批准”描述。
- G08 已闭环：PostgreSQL checkpoint 20/20 通过，覆盖同 context 完成结果缓存、
  重复完成拒绝、持久化契约校验和并发重复 claim。
- 阶段九 v2 共 **182 项：182 通过、0 失败、0 错误、0 跳过**，无缺失或变更覆盖；
  源指纹为
  `bd0110806f464ec42b9f48e9a5142b036799281dba96dc2c927658da36e3047f`。
- 完整门禁保持 `BLOCKED`，`gapCount=7`；未通过删除断言、跳过测试或把未执行项改成
  通过来制造绿色结果。

## 首次验收结果

共 **158 项：76 通过、82 失败、0 错误、0 跳过**，包含 150 项应用边界测试与
8 项执行器自测。完整门禁为 `BLOCKED`，无缺失批次/数量变化，另有 8 类未验收范围。

| 批次 | 通过 / 总数 | 失败 | 批次耗时 |
| --- | ---: | ---: | ---: |
| Java | 38 / 76 | 38 | 9.595 秒 |
| Python | 19 / 63 | 44 | 12.805 秒 |
| 前端证据组件 | 7 / 7 | 0 | 2.019 秒 |
| Playwright 桌面/移动端 | 4 / 4 | 0 | 10.735 秒 |
| 执行器自测 | 8 / 8 | 0 | 3.778 秒 |
| 前端类型检查及构建 | 批次通过，不计用例数 | 0 | 11.547 秒 |

串行批次总耗时约 50.479 秒，不含人工分析和附加扫描。这是小规模边界回归，
不提供吞吐量、生产 p95 或真实模型攻击成功率。完整命令保存在 `json/batches.json`。
Java 已通过 `test-compile`；三个新增 Python 文件的 Ruff 检查通过。

首轮 MCP WireMock 模板未正确关联 JSON-RPC 响应，正常基线超时，初轮负例的“抛异常”
不得算有效拒绝证据。已改为 JDK 本机 HTTP 服务，用结构化 JSON 保留请求 id；
每个负例先跑正常基线且不允许超时充当拒绝结果，验收批次已无执行错误。
初轮前端使用不被现有配置接受的相对 API 地址，已改成本机完整 origin；
移动端测试增加页面就绪等待及“冲突”页签导航。以上属于测试修正，不计应用漏洞。

附加 Gitleaks 检查：

- 当前源码零命中，扫描约 6.91 MB；按已有 `.gitleaks.toml` 排除依赖/产物/报告。
- 本次验收证据使用 `qa/security/gitleaks-artifacts.toml` 独立扫描约 866 KB，零命中，
  未增加豁免规则；没有解包浏览器 trace ZIP，此项不证明压缩包内任意秘密不存在。
- 结果分别保存在 `json/gitleaks-source.json`、`json/gitleaks-evidence.json`。

## 执行边界

- 仅合成数据。Java 使用真实 Guardrail、规则引擎、Schema、A2A 委派和官方 MCP 客户端；模型响应、A2A handler、事务 repository 使用测试替身。
- Python 使用 FastAPI TestClient、真实临时 LanceDB、Mock embedding/rerank、内存图存储；检索测试显式禁止 `httpx.AsyncClient.send` 外联。
- OCR 测试把合成指令注入 OCR 识别结果，并验证是否进入视觉模型。不是对真实图片的 OCR 或多模态模型攻击成功率评测。
- MCP 合成服务只监听 `127.0.0.1` 随机端口；每个恶意响应测试先完成同端口正常基线，再切换响应。没有访问样本中的内网或外部 URL。
- Playwright 只访问本机预览服务器，拦截合成 API 数据并阻断跨域请求；未点击任何费用操作，也未调用真实供应商。
- Java 21.0.8、Python 3.12.14、Node 26.4.0。默认无需 Docker、PostgreSQL、Redis、Kafka 或 Neo4j 服务。
- 本轮未获真实模型调用授权，未调用真实模型、地图、天气或支付服务；没有压力测试。

## 回归范围

共享语料 `qa/security/ai-stage9-corpus.json` 包含 17 个直接注入、6 个间接注入、
3 个正常输入、8 个危险地址样本；语言覆盖中文、英文、全角和零宽字符。
字符串长度与结构攻击由测试内生成，不存储巨量文本。

| 用户要求 | 测试及结论边界 |
| --- | --- |
| 忽略规则、提示词/密钥外泄、跳过预算/时间、支付/退订、未授权工具 | Java 检测器与 FastAPI 模型前边界；检测失败不等于真实模型已泄密 |
| 攻略、记忆中的目标覆盖、跨用户读取、URL 外联、上下文外泄、伪造证据 | 6 种指令分别放入攻略正文、用户记忆、标题及长文尾部，观察进入 reranker 的数据 |
| OCR 注入 | 6 种识别结果注入；检查视觉模型调用是否为零 |
| RAG 用户与租户隔离、删除 | 同 chunkId、不同私有内容的 A/B 基线；删除 A 后 A 无召回且 B 保留；不同租户无召回 |
| 城市隔离与索引污染 | 不同城市/过期内容过滤；相同 chunkId 跨城市写入覆盖为独立负例 |
| 来源与空检索 | 空 sourceId 拒绝、空结果 evidenceInsufficient 通过；长文来源集合断言已通过；外部来源真实性未验证 |
| 上下文有界 | 超过 12000 字符的 chunk 拒绝；合法长文的单 excerpt 和总预算检查已通过 |
| MCP 未登记服务、工具、权限 | 域名拒绝、Agent 工具 allowlist、额外 URL 参数与负金额 Schema；booking_parse 另测资源归属前置条件 |
| MCP 身份变化、名称碰撞、非法输出 | 官方客户端连接本机合成服务，正常基线后更换 serverInfo、重复工具名或返回数组 data |
| MCP 注入与成本去重 | 工具响应进入 AgentToolSupport；四类成本动作在 HITL 服务层验证确认及相同 actionId 二次执行拒绝 |
| A2A 冒充、taskId、重放、字段与体积 | 未登记 Agent、返回 Agent 不符、伪造结果 taskId、旧 contextId、额外 userId/totalBudget、1.1 MB 输出 |
| A2A 能力与身份 | Agent Card 与工具能力不一致；任务 owner 相同但未认证的读写检查 |
| 非法模型输出 | 非 JSON、Markdown、未知字段、超长字段/整体响应、尾随 JSON、重复键、脚本命令字段、负金额 |
| 确定性复核 | 正常基线、重叠时间、超预算、伪造汇总、负金额、倒置时间、不存在的 evidenceId |
| 前端文本与 URL | 证据抽屉组件验证 HTML/JS 为文本、javascript/data/file 不生成链接、空来源不造链接；工作台和证据抽屉均跑桌面/移动端 |

## 已证实的问题

以下按边界归类，**失败断言数量不是漏洞数量，也不代表已证实远程可利用性**。
优先级是修复顺序，不冒充 CVSS 或正式漏洞评级。

| 编号 | 优先级 | 观察与限制 | 生产代码 |
| --- | --- | --- | --- |
| S9-01 | P1 | **已修复并复验。** Java/Python 对 17 个直接注入执行一致的 Unicode 规范化和高风险意图检测，正常对照未误拒绝 | `guardrail/PromptInjectionGuard.java`、`core/guardrails.py` |
| S9-02 | P1 | **已修复并复验。** 攻略正文、用户记忆、标题、长文尾部及 OCR 注入均在模型调用前拒绝 | `retrieval/service.py`、`core/guardrails.py` |
| S9-03 | P1 | **已修复并复验。** 拒绝事件记录错误码、来源和规则信号，不记录输入全文 | Java `requireSafe`、Python `require_safe_prompt_input` |
| S9-04 | P1 | **已修复并复验。** 公共索引以 `city + chunkId` 合并和融合，同名 chunk 可在不同城市并存 | `storage/lancedb_store.py` 的 `upsert_public` |
| S9-05 | P1 | **已修复并复验。** 完整内容先扫描再截断，标题独立扫描，不可信前缀计入 1000 字符总上限；合法长文和尾部注入断言通过 | `core/guardrails.py`、`retrieval/service.py` |
| S9-06 | P1 | **已修复并复验。** 客户端固定 serverInfo、拒绝重复名和 manifest 漂移，并按本地输出 Schema 校验结果 | `skill/OfficialMcpToolGateway.java`、`McpSchemaCatalog.java` |
| S9-07 | P0 | **已修复并复验。** `booking_parse` 与 `itinerary_store` 在业务服务前校验运行中 Agent、owner task、plan、trip 和目标资源关系 | `skill/McpToolCatalog.java`、`McpResourceAuthorizer.java` |
| S9-08 | P1 | **已修复并复验。** 工具 data/warnings 中的注入信号不会作为成功上下文进入 Agent | `agent/AgentToolSupport.java` |
| S9-09 | P0 | **已修复并复验。** 接收端绑定 SDK Task ID、contextId、结果 taskId 和目标 Agent，并按具体输出类型与 1 MiB 上限验证 | `agent/a2a/A2ADelegationService.java` |
| S9-10 | P1 | **已修复并复验。** 任务读写要求认证；Agent 注册时 Card 身份/技能必须与定义及 allowlist 完全一致 | `TenantTaskAuthorizationProvider.java`、`AgentRegistry.java` |
| S9-11 | P1 | **已修复并复验。** 严格拒绝尾随 JSON、重复键和负金额，一次修复继承原 system policy | `integration/model/StructuredOutputService.java`、`result/PlanDraftResult.java` |
| S9-12 | P1 | **已修复并复验。** 规则引擎重新计算预算并校验负数、时间方向和证据归属 | `application/planning/PlanRuleEngine.java` |

HITL 四类成本动作测试通过：未确认不能执行，其他 actor 不能确认，确认不等于执行，
相同 actionId 第二次执行被拒绝。没有注册真实费用 MCP adapter，因此它不证明供应商
副作用的并发幂等、事务原子性或“恰好一次”交付。

## 尚未验收

语料中的 G01-G07 以 `NOT_EXECUTED` 进入完整阶段门禁，不通过更改状态豁免：

1. 真实模型不泄露 system prompt、合成秘密和配置的语义回归。
2. 真实图片 OCR、视觉模型与 Prompt Injection 的完整链路。
3. 文档来源确实存在、可信发布者和溯源真实性；内部 sourceId 非空不是来源证明。
4. DNS 解析后网络范围、重定向、DNS 变化和服务端密码学身份验证。
5. 本地 userId、tripId、taskId 归属链已绑定；远程 A2A Agent 的密码学身份认证
   尚未验收。
6. 真实费用适配器的确认授权、并发去重与供应商执行原子性。
7. 持久化输出到浏览器的完整链路及原始 Jaeger 隐私。非法 checkpoint 输出事务回滚
   已由嵌入式 PostgreSQL 集成测试覆盖。

原 G08 已从 gap 清单移除：同 context 完成结果重放、持久化去重、重复完成及并发
claim 已纳入 `AgentCheckpointSafetyIntegrationTest`，并作为阶段九固定 20 项覆盖。

## 重复执行

```sh
# 使用新目录，保留前次证据；完整门禁当前预期非零。
make test-ai-security QA_RUN_ID=stage9-new-run

# 本次修复复验命令
make test-ai-security QA_RUN_ID=20260915T-stage9-a2a-mcp-remediation
make test-ai-security QA_RUN_ID=20260915T-stage9-prompt-remediation
make test-ai-security QA_RUN_ID=20260915T-stage9-remediation-final

# 认证与契约更新后的最终完整门禁
make test-ai-security QA_RUN_ID=20260916T-stage9-auth-final
```

执行器会运行全部独立批次，某一批失败不阻断其他批次的证据采集：
Java → Python → 前端组件 → 新构建 → Playwright；最先执行报告执行器自测。
每批限制 600 秒，MCP 请求限制 3 秒，浏览器 1 worker、0 retries。
运行 ID 不允许路径穿越，已存在目录直接拒绝；构建失败不会复用旧前端产物。
随机选择本机端口，如竞争占用会由 strictPort 失败，不复用未知服务。

`sourceSha256` 对 Prompt、模型与检索代码、工具路由、契约及相关测试/配置计算指纹。
运行期间指纹变化时阻断门禁。当前语料版本 `ai-security-stage9-v2`。
修改语料导致用例数变化时，必须同步评审执行器的预期覆盖数量；不能只删除负例。
该指纹是复现定位信息，不替代依赖锁文件或可重复构建证明。

JUnit 成功/失败/错误/跳过由原始测试结果解析。记录去掉 JVM properties 和测试 stdout，
日志及失败内容脱敏，超长攻击值压缩；保留用例 ID、错误栈位置和证据文件路径。
同名浏览器测试包含 desktop/mobile 标识。无真实可关联 Trace 的测试保存空 traceIds/taskIds，
不伪造 Jaeger 证据；合成业务 taskId 不当作真实用户标识。

当前工作区无 `.git`，未执行差异审查或 Git 历史扫描。
本轮没有修改 `.env`、真实凭据或已有业务数据。临时 LanceDB、MCP 服务和浏览器预览
服务器由各测试的清理逻辑回收；浏览器 trace/截图只含合成数据。

## 文件清单

- `qa/security/ai-stage9-corpus.json`：共享攻击样本、正常对照与未验收范围。
- `qa/scripts/run_stage9.py`、`qa/tests/test_stage9_harness.py`：有界执行、结果解析、脱敏与完整门禁。
- `Makefile` 的 `test-ai-security`、`qa/README.md`：统一复跑入口。
- Java `Stage9PromptSecurityTest`、`Stage9ActionSecurityTest`、`Stage9ModelOutputSecurityTest`、
  `Stage9RuleSecurityTest`、`Stage9A2ASecurityTest`、`Stage9McpSecurityTest`、
  `AgentCheckpointSafetyIntegrationTest` 和 `AiSecurityCorpus`。
- `services/ai-retrieval-service/tests/test_stage9_ai_security.py`：API/RAG/OCR/地址与事件回归。
- `apps/web/src/components/results/stage9-ai-security.test.tsx`、
  `apps/web/e2e/stage9-security.spec.ts`、`apps/web/playwright.ai-security.config.ts`；
  复用现有 `stage8-security.spec.ts` 和浏览器安全配置，未修改前端业务实现。

## 修复顺序

1. 阶段八可信 JWT principal 与用户资源授权已完成；MCP/A2A 的任务、用户、旅行、
   资源授权及能力绑定已纳入本地回归。
2. A2A 接收关联、MCP 本地 Schema/服务身份及 checkpoint 持久化重放已完成；继续补
   远程身份和真实成本供应商证据。
3. RAG 城市写入边界已完成；继续补外部来源存在性和发布者真实性。
4. 严格 JSON、金额/时间/证据确定性校验、Prompt 检测与脱敏安全事件已完成。
5. 原样重跑负例；在另行授权后补有界真实模型与 OCR 基线，最后合并阶段八发布门禁。
