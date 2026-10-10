# SystemApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**getSystemHealth**](SystemApi.md#getsystemhealth) | **GET** /api/v1/health | Get public API availability and provider mode |
| [**getSystemStatus**](SystemApi.md#getsystemstatus) | **GET** /api/v1/system/status | Get sanitized dependency and capability availability |



## getSystemHealth

> SystemHealth getSystemHealth()

Get public API availability and provider mode

### Example

```ts
import {
  Configuration,
  SystemApi,
} from '@tripagent/tripagent-v1-sdk';
import type { GetSystemHealthRequest } from '@tripagent/tripagent-v1-sdk';

async function example() {
  console.log("🚀 Testing @tripagent/tripagent-v1-sdk SDK...");
  const api = new SystemApi();

  try {
    const data = await api.getSystemHealth();
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters

This endpoint does not need any parameter.

### Return type

[**SystemHealth**](SystemHealth.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Public API health |  -  |
| **400** | Malformed request or idempotency key |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## getSystemStatus

> SystemStatus getSystemStatus()

Get sanitized dependency and capability availability

### Example

```ts
import {
  Configuration,
  SystemApi,
} from '@tripagent/tripagent-v1-sdk';
import type { GetSystemStatusRequest } from '@tripagent/tripagent-v1-sdk';

async function example() {
  console.log("🚀 Testing @tripagent/tripagent-v1-sdk SDK...");
  const api = new SystemApi();

  try {
    const data = await api.getSystemStatus();
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters

This endpoint does not need any parameter.

### Return type

[**SystemStatus**](SystemStatus.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Sanitized system status |  -  |
| **400** | Malformed request or idempotency key |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)

