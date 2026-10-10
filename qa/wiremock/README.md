# 可控外部工具桩

WireMock 在本地监听 `9099`，提供地图、天气、汇率和路线合成响应。

默认请求返回成功结果。设置请求头 `X-QA-Scenario` 可切换边界行为：

| 值 | 行为 |
| --- | --- |
| `timeout` | 延迟 3 秒返回 |
| `rate-limit` | 返回 HTTP 429 和 `Retry-After` |
| `error` | 返回 HTTP 503 |
| `malformed` | 返回无法解析的 JSON |

可用路径为：

- `GET /maps/{zoom}/{x}/{y}.png`
- `POST /tools/weather`
- `POST /tools/exchange-rate`
- `POST /tools/route`

这些桩只包含合成数据，不代理或转发任何第三方请求。
