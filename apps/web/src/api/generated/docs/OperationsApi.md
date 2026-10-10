# OperationsApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**listDeadLetters**](OperationsApi.md#listdeadletters) | **GET** /api/v1/operations/dead-letters | List retained dead-letter events |
| [**replayDeadLetter**](OperationsApi.md#replaydeadletteroperation) | **POST** /api/v1/operations/dead-letters/{deadLetterId}/replay | Republish one retained dead-letter event |



## listDeadLetters

> DeadLetterList listDeadLetters(xOperationsToken, status)

List retained dead-letter events

### Example

```ts
import {
  Configuration,
  OperationsApi,
} from '@tripagent/tripagent-v1-sdk';
import type { ListDeadLettersRequest } from '@tripagent/tripagent-v1-sdk';

async function example() {
  console.log("🚀 Testing @tripagent/tripagent-v1-sdk SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: OperationsToken
    apiKey: "YOUR API KEY",
  });
  const api = new OperationsApi(config);

  const body = {
    // string | Backend-only operations credential injected from a secret store
    xOperationsToken: xOperationsToken_example,
    // DeadLetterStatus (optional)
    status: ...,
  } satisfies ListDeadLettersRequest;

  try {
    const data = await api.listDeadLetters(body);
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
| **xOperationsToken** | `string` | Backend-only operations credential injected from a secret store | [Defaults to `undefined`] |
| **status** | `DeadLetterStatus` |  | [Optional] [Defaults to `undefined`] [Enum: PENDING, REPLAYED] |

### Return type

[**DeadLetterList**](DeadLetterList.md)

### Authorization

[OperationsToken](../README.md#OperationsToken)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Dead-letter records ordered by newest first |  -  |
| **400** | Malformed request or idempotency key |  -  |
| **401** | Authentication credential is missing or invalid |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## replayDeadLetter

> DeadLetterRecord replayDeadLetter(xOperationsToken, idempotencyKey, deadLetterId, replayDeadLetterRequest)

Republish one retained dead-letter event

### Example

```ts
import {
  Configuration,
  OperationsApi,
} from '@tripagent/tripagent-v1-sdk';
import type { ReplayDeadLetterOperationRequest } from '@tripagent/tripagent-v1-sdk';

async function example() {
  console.log("🚀 Testing @tripagent/tripagent-v1-sdk SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: OperationsToken
    apiKey: "YOUR API KEY",
  });
  const api = new OperationsApi(config);

  const body = {
    // string | Backend-only operations credential injected from a secret store
    xOperationsToken: xOperationsToken_example,
    // string | Caller-generated key scoped to operation and authenticated actor
    idempotencyKey: idempotencyKey_example,
    // string
    deadLetterId: 38400000-8cf0-11bd-b23e-10b96e4ef00d,
    // ReplayDeadLetterRequest
    replayDeadLetterRequest: ...,
  } satisfies ReplayDeadLetterOperationRequest;

  try {
    const data = await api.replayDeadLetter(body);
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
| **xOperationsToken** | `string` | Backend-only operations credential injected from a secret store | [Defaults to `undefined`] |
| **idempotencyKey** | `string` | Caller-generated key scoped to operation and authenticated actor | [Defaults to `undefined`] |
| **deadLetterId** | `string` |  | [Defaults to `undefined`] |
| **replayDeadLetterRequest** | [ReplayDeadLetterRequest](ReplayDeadLetterRequest.md) |  | |

### Return type

[**DeadLetterRecord**](DeadLetterRecord.md)

### Authorization

[OperationsToken](../README.md#OperationsToken)

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **202** | Replay event accepted into the transactional outbox |  -  |
| **400** | Malformed request or idempotency key |  -  |
| **401** | Authentication credential is missing or invalid |  -  |
| **404** | Resource not found in the authorized scope |  -  |
| **409** | Idempotency, state, or version conflict |  -  |
| **422** | Domain or field validation failed |  -  |
| **429** | Operations rate limit exceeded |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)

