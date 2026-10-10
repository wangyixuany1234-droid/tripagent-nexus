# FeedbackApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**createFeedback**](FeedbackApi.md#createfeedbackoperation) | **POST** /api/v1/feedback | Record user feedback for a trip or plan |
| [**listFeedback**](FeedbackApi.md#listfeedback) | **GET** /api/v1/feedback | List feedback belonging to one trip |



## createFeedback

> Feedback createFeedback(idempotencyKey, createFeedbackRequest)

Record user feedback for a trip or plan

### Example

```ts
import {
  Configuration,
  FeedbackApi,
} from '@tripagent/tripagent-v1-sdk';
import type { CreateFeedbackOperationRequest } from '@tripagent/tripagent-v1-sdk';

async function example() {
  console.log("🚀 Testing @tripagent/tripagent-v1-sdk SDK...");
  const config = new Configuration({ 
    // Configure HTTP bearer authorization: BearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new FeedbackApi(config);

  const body = {
    // string | Caller-generated key scoped to operation and authenticated actor
    idempotencyKey: idempotencyKey_example,
    // CreateFeedbackRequest
    createFeedbackRequest: ...,
  } satisfies CreateFeedbackOperationRequest;

  try {
    const data = await api.createFeedback(body);
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
| **createFeedbackRequest** | [CreateFeedbackRequest](CreateFeedbackRequest.md) |  | |

### Return type

[**Feedback**](Feedback.md)

### Authorization

[BearerAuth](../README.md#BearerAuth)

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **201** | Feedback created |  -  |
| **400** | Malformed request or idempotency key |  -  |
| **404** | Resource not found in the authorized scope |  -  |
| **409** | Idempotency, state, or version conflict |  -  |
| **422** | Domain or field validation failed |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## listFeedback

> FeedbackList listFeedback(tripId)

List feedback belonging to one trip

### Example

```ts
import {
  Configuration,
  FeedbackApi,
} from '@tripagent/tripagent-v1-sdk';
import type { ListFeedbackRequest } from '@tripagent/tripagent-v1-sdk';

async function example() {
  console.log("🚀 Testing @tripagent/tripagent-v1-sdk SDK...");
  const config = new Configuration({ 
    // Configure HTTP bearer authorization: BearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new FeedbackApi(config);

  const body = {
    // string
    tripId: 38400000-8cf0-11bd-b23e-10b96e4ef00d,
  } satisfies ListFeedbackRequest;

  try {
    const data = await api.listFeedback(body);
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
| **tripId** | `string` |  | [Defaults to `undefined`] |

### Return type

[**FeedbackList**](FeedbackList.md)

### Authorization

[BearerAuth](../README.md#BearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Feedback list |  -  |
| **400** | Malformed request or idempotency key |  -  |
| **404** | Resource not found in the authorized scope |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)

