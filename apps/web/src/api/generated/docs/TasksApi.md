# TasksApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**cancelTask**](TasksApi.md#canceltask) | **POST** /api/v1/tasks/{taskId}/cancel | Cancel a non-terminal task |
| [**getTask**](TasksApi.md#gettask) | **GET** /api/v1/tasks/{taskId} | Get asynchronous task state |
| [**listTaskAgentExecutions**](TasksApi.md#listtaskagentexecutions) | **GET** /api/v1/tasks/{taskId}/agents | List backend-observed Agent execution states for a planning task |
| [**streamTaskEvents**](TasksApi.md#streamtaskevents) | **GET** /api/v1/tasks/{taskId}/events | Stream persisted and live task progress |



## cancelTask

> TaskRecord cancelTask(idempotencyKey, taskId)

Cancel a non-terminal task

### Example

```ts
import {
  Configuration,
  TasksApi,
} from '@tripagent/tripagent-v1-sdk';
import type { CancelTaskRequest } from '@tripagent/tripagent-v1-sdk';

async function example() {
  console.log("🚀 Testing @tripagent/tripagent-v1-sdk SDK...");
  const config = new Configuration({ 
    // Configure HTTP bearer authorization: BearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new TasksApi(config);

  const body = {
    // string | Caller-generated key scoped to operation and authenticated actor
    idempotencyKey: idempotencyKey_example,
    // string
    taskId: 38400000-8cf0-11bd-b23e-10b96e4ef00d,
  } satisfies CancelTaskRequest;

  try {
    const data = await api.cancelTask(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **idempotencyKey** | `string` | Caller-generated key scoped to operation and authenticated actor | [Defaults to `undefined`] |
| **taskId** | `string` |  | [Defaults to `undefined`] |

### Return type

[**TaskRecord**](TaskRecord.md)

### Authorization

[BearerAuth](../README.md#BearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Cancelled task |  -  |
| **400** | Malformed request or idempotency key |  -  |
| **404** | Resource not found in the authorized scope |  -  |
| **409** | Idempotency, state, or version conflict |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## getTask

> TaskRecord getTask(taskId)

Get asynchronous task state

### Example

```ts
import {
  Configuration,
  TasksApi,
} from '@tripagent/tripagent-v1-sdk';
import type { GetTaskRequest } from '@tripagent/tripagent-v1-sdk';

async function example() {
  console.log("🚀 Testing @tripagent/tripagent-v1-sdk SDK...");
  const config = new Configuration({ 
    // Configure HTTP bearer authorization: BearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new TasksApi(config);

  const body = {
    // string
    taskId: 38400000-8cf0-11bd-b23e-10b96e4ef00d,
  } satisfies GetTaskRequest;

  try {
    const data = await api.getTask(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **taskId** | `string` |  | [Defaults to `undefined`] |

### Return type

[**TaskRecord**](TaskRecord.md)

### Authorization

[BearerAuth](../README.md#BearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Task |  -  |
| **400** | Malformed request or idempotency key |  -  |
| **404** | Resource not found in the authorized scope |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## listTaskAgentExecutions

> AgentExecutionList listTaskAgentExecutions(taskId)

List backend-observed Agent execution states for a planning task

### Example

```ts
import {
  Configuration,
  TasksApi,
} from '@tripagent/tripagent-v1-sdk';
import type { ListTaskAgentExecutionsRequest } from '@tripagent/tripagent-v1-sdk';

async function example() {
  console.log("🚀 Testing @tripagent/tripagent-v1-sdk SDK...");
  const config = new Configuration({ 
    // Configure HTTP bearer authorization: BearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new TasksApi(config);

  const body = {
    // string
    taskId: 38400000-8cf0-11bd-b23e-10b96e4ef00d,
  } satisfies ListTaskAgentExecutionsRequest;

  try {
    const data = await api.listTaskAgentExecutions(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **taskId** | `string` |  | [Defaults to `undefined`] |

### Return type

[**AgentExecutionList**](AgentExecutionList.md)

### Authorization

[BearerAuth](../README.md#BearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Agent execution list |  -  |
| **400** | Malformed request or idempotency key |  -  |
| **404** | Resource not found in the authorized scope |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## streamTaskEvents

> string streamTaskEvents(taskId, lastEventID)

Stream persisted and live task progress

### Example

```ts
import {
  Configuration,
  TasksApi,
} from '@tripagent/tripagent-v1-sdk';
import type { StreamTaskEventsRequest } from '@tripagent/tripagent-v1-sdk';

async function example() {
  console.log("🚀 Testing @tripagent/tripagent-v1-sdk SDK...");
  const config = new Configuration({ 
    // Configure HTTP bearer authorization: BearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new TasksApi(config);

  const body = {
    // string
    taskId: 38400000-8cf0-11bd-b23e-10b96e4ef00d,
    // string | Last processed event UUID. Later retained events are replayed. (optional)
    lastEventID: 38400000-8cf0-11bd-b23e-10b96e4ef00d,
  } satisfies StreamTaskEventsRequest;

  try {
    const data = await api.streamTaskEvents(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **taskId** | `string` |  | [Defaults to `undefined`] |
| **lastEventID** | `string` | Last processed event UUID. Later retained events are replayed. | [Optional] [Defaults to `undefined`] |

### Return type

**string**

### Authorization

[BearerAuth](../README.md#BearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `text/event-stream`, `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Server-Sent Events stream. The SSE &#x60;id&#x60; equals &#x60;eventId&#x60;; clients use &#x60;Last-Event-ID&#x60; to resume without replaying earlier events.  |  -  |
| **400** | Malformed request or idempotency key |  -  |
| **404** | Resource not found in the authorized scope |  -  |
| **410** | Requested event is outside retained history |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)

