# 阶段五性能测试

本目录只用于本地或已授权测试环境。默认大规模场景要求后端返回
`providerMode=MOCK`，并使用 Mock OCR、Mock MCP 和本地 WireMock 工具。

## 场景

| 场景 | 默认模型 |
| --- | --- |
| `smoke` | 2 VU，5 分钟 |
| `load` | 25、50、100 VU，每档保持 10 分钟 |
| `stress` | 100、200、400 VU |
| `spike` | 30 秒内从 20 VU 升到 300 VU |
| `soak` | 50 VU，2 小时，可配置到 8 小时 |
| `sse` | 100、500、1000 条连接 |
| `idempotency` | 同一幂等键并发提交 20 次 |
| `race` | 同一 READY 计划版本并发确认 20 次 |
| `canary` | 真实模型独立小流量验证 |

## 执行

先启动隔离测试环境：

```bash
make test-env-up
```

Smoke 可直接执行：

```bash
make test-performance-smoke
```

Load、Stress、Spike、Soak 和 SSE 必须显式授权：

```bash
QA_HIGH_LOAD_APPROVED=true QA_MOCK_EXTERNAL_TOOLS_CONFIRMED=true \
  make test-performance-load
QA_HIGH_LOAD_APPROVED=true QA_MOCK_EXTERNAL_TOOLS_CONFIRMED=true \
  make test-performance-stress
QA_HIGH_LOAD_APPROVED=true QA_MOCK_EXTERNAL_TOOLS_CONFIRMED=true \
  make test-performance-spike
QA_HIGH_LOAD_APPROVED=true QA_MOCK_EXTERNAL_TOOLS_CONFIRMED=true \
  make test-performance-soak
QA_HIGH_LOAD_APPROVED=true QA_MOCK_EXTERNAL_TOOLS_CONFIRMED=true \
  make test-performance-sse
```

并发一致性场景：

```bash
make test-performance-idempotency
make test-performance-race
```

真实模型 Canary 必须使用独立环境，并同时满足：

```bash
REAL_MODEL_TEST_ENABLED=true \
QA_REAL_MODEL_CANARY_APPROVED=true \
QA_MOCK_EXTERNAL_TOOLS_CONFIRMED=true \
QA_CANARY_BACKEND_LIMITS_CONFIRMED=true \
QA_REAL_MODEL_MAX_CONCURRENCY=2 \
QA_REAL_MODEL_MAX_CALLS=5 \
MODEL_MAX_CONCURRENCY=2 \
MODEL_MAX_TOTAL_CALLS=5 \
make test-performance-canary
```

## 覆盖参数

所有门槛均可通过环境变量覆盖：

- `QA_SYNC_P95_MS`、`QA_SYNC_P99_MS`、`QA_SYNC_ERROR_RATE`
- `QA_WRITE_P95_MS`、`QA_WRITE_P99_MS`、`QA_WRITE_ERROR_RATE`
- `QA_ASYNC_SUBMIT_P95_MS`、`QA_ASYNC_SUBMIT_P99_MS`
- `QA_ASYNC_COMPLETION_P95_MS`、`QA_ASYNC_ERROR_RATE`
- `QA_MAX_TASK_STUCK_RATE`、`QA_MAX_DUPLICATE_RESOURCES`
- `QA_SSE_MIN_CONNECT_SUCCESS_RATE`
- `QA_SSE_MIN_RECONNECT_SUCCESS_RATE`
- `QA_SSE_MAX_DUPLICATE_EVENTS`、`QA_SSE_MAX_LOST_EVENTS`
- `QA_SSE_MAX_SERVER_CPU_PERCENT`
- `QA_MAX_FINAL_KAFKA_LAG`、`QA_MAX_FINAL_HIKARI_PENDING`
- `QA_MAX_FINAL_HIKARI_ACTIVE_INCREASE`
- `QA_MAX_FINAL_THREAD_GROWTH_PERCENT`
- `QA_MAX_FINAL_REDIS_CLIENT_INCREASE`
- `QA_MAX_FINAL_TCP_CONNECTION_INCREASE`

负载模型可通过 `QA_VUS`、`QA_DURATION`、`QA_STAGES_JSON`、
`QA_SYNC_WEIGHT`、`QA_WRITE_WEIGHT` 和 `QA_ASYNC_WEIGHT` 覆盖。Soak
时长上限为 8 小时。

资源采集参数可通过以下环境变量调整：

- `QA_METRICS_INTERVAL_SECONDS`：采样间隔，默认 5 秒；
- `QA_METRICS_MAX_WORKERS`：并行只读探针数，默认 4；
- `QA_METRICS_HTTP_TIMEOUT_SECONDS`：HTTP 探针超时，默认 8 秒；
- `QA_METRICS_COMMAND_TIMEOUT_SECONDS`：Docker 命令超时，默认 15 秒；
- `QA_RECOVERY_OBSERVE_SECONDS`：流量结束后的恢复观察时间，默认 60 秒。

## 输出

每次执行在 `output/test-results/<run-id>/` 下生成：

- `performance/k6-<profile>-summary.json`
- `html/k6-<profile>-report.html`
- `performance/resources-<profile>.ndjson`
- `json/resources-<profile>-summary.json`
- `json/stage5-<profile>-summary.json`
- `html/stage5-<profile>-report.html`
- `logs/k6-<profile>.log`

报告仅包含聚合指标和合成标识，不保存请求正文、授权头或 OCR 内容。
