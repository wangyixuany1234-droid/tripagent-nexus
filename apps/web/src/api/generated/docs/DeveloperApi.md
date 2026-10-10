# DeveloperApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**getDeveloperTrace**](DeveloperApi.md#getdevelopertrace) | **GET** /api/v1/developer/traces/{traceId} | Get a sanitized trace in development or for administrators |



## getDeveloperTrace

> DeveloperTrace getDeveloperTrace(traceId)

Get a sanitized trace in development or for administrators

### Example

```ts
import {
  Configuration,
  DeveloperApi,
} from '@tripagent/tripagent-v1-sdk';
import type { GetDeveloperTraceRequest } from '@tripagent/tripagent-v1-sdk';

async function example() {
  console.log("🚀 Testing @tripagent/tripagent-v1-sdk SDK...");
  const config = new Configuration({ 
    // Configure HTTP bearer authorization: BearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new DeveloperApi(config);

  const body = {
    // string
    traceId: traceId_example,
  } satisfies GetDeveloperTraceRequest;

  try {
    const data = await api.getDeveloperTrace(body);
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
| **traceId** | `string` |  | [Defaults to `undefined`] |

### Return type

[**DeveloperTrace**](DeveloperTrace.md)

### Authorization

[BearerAuth](../README.md#BearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Sanitized trace |  -  |
| **400** | Malformed request or idempotency key |  -  |
| **403** | Authenticated actor is not allowed to access this resource |  -  |
| **404** | Resource not found in the authorized scope |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)

