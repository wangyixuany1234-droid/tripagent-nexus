# WorkbenchApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**listWorkbenchTasks**](WorkbenchApi.md#listworkbenchtasks) | **GET** /api/v1/workbench/tasks | List user-scoped travel tasks with filters |



## listWorkbenchTasks

> WorkbenchTaskList listWorkbenchTasks(userId, destination, startDate, endDate, status)

List user-scoped travel tasks with filters

### Example

```ts
import {
  Configuration,
  WorkbenchApi,
} from '@tripagent/tripagent-v1-sdk';
import type { ListWorkbenchTasksRequest } from '@tripagent/tripagent-v1-sdk';

async function example() {
  console.log("🚀 Testing @tripagent/tripagent-v1-sdk SDK...");
  const config = new Configuration({ 
    // Configure HTTP bearer authorization: BearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new WorkbenchApi(config);

  const body = {
    // string
    userId: 38400000-8cf0-11bd-b23e-10b96e4ef00d,
    // string (optional)
    destination: destination_example,
    // Date (optional)
    startDate: 2013-10-20,
    // Date (optional)
    endDate: 2013-10-20,
    // TaskStatus (optional)
    status: ...,
  } satisfies ListWorkbenchTasksRequest;

  try {
    const data = await api.listWorkbenchTasks(body);
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
| **userId** | `string` |  | [Defaults to `undefined`] |
| **destination** | `string` |  | [Optional] [Defaults to `undefined`] |
| **startDate** | `Date` |  | [Optional] [Defaults to `undefined`] |
| **endDate** | `Date` |  | [Optional] [Defaults to `undefined`] |
| **status** | `TaskStatus` |  | [Optional] [Defaults to `undefined`] [Enum: CREATED, QUEUED, RUNNING, WAITING_CONFIRMATION, SUCCEEDED, PARTIAL_SUCCESS, FAILED, CANCELLED] |

### Return type

[**WorkbenchTaskList**](WorkbenchTaskList.md)

### Authorization

[BearerAuth](../README.md#BearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Filtered workbench tasks |  -  |
| **400** | Malformed request or idempotency key |  -  |
| **422** | Domain or field validation failed |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)

