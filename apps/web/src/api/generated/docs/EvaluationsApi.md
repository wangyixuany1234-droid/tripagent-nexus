# EvaluationsApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**getEvaluationRun**](EvaluationsApi.md#getevaluationrun) | **GET** /api/v1/evaluations/runs/{runId} | Get aggregate metrics, sample outcomes, failures, and execution environment |
| [**startEvaluationRun**](EvaluationsApi.md#startevaluationrun) | **POST** /api/v1/evaluations/runs | Queue a versioned Harness Engineering dataset replay |



## getEvaluationRun

> EvaluationRun getEvaluationRun(xOperationsToken, runId)

Get aggregate metrics, sample outcomes, failures, and execution environment

### Example

```ts
import {
  Configuration,
  EvaluationsApi,
} from '@tripagent/tripagent-v1-sdk';
import type { GetEvaluationRunRequest } from '@tripagent/tripagent-v1-sdk';

async function example() {
  console.log("🚀 Testing @tripagent/tripagent-v1-sdk SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: OperationsToken
    apiKey: "YOUR API KEY",
  });
  const api = new EvaluationsApi(config);

  const body = {
    // string | Backend-only operations credential injected from a secret store
    xOperationsToken: xOperationsToken_example,
    // string
    runId: 38400000-8cf0-11bd-b23e-10b96e4ef00d,
  } satisfies GetEvaluationRunRequest;

  try {
    const data = await api.getEvaluationRun(body);
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
| **runId** | `string` |  | [Defaults to `undefined`] |

### Return type

[**EvaluationRun**](EvaluationRun.md)

### Authorization

[OperationsToken](../README.md#OperationsToken)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Evaluation run report |  -  |
| **400** | Malformed request or idempotency key |  -  |
| **401** | Authentication credential is missing or invalid |  -  |
| **404** | Resource not found in the authorized scope |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## startEvaluationRun

> TaskAccepted startEvaluationRun(xOperationsToken, idempotencyKey, createEvaluationRunRequest)

Queue a versioned Harness Engineering dataset replay

### Example

```ts
import {
  Configuration,
  EvaluationsApi,
} from '@tripagent/tripagent-v1-sdk';
import type { StartEvaluationRunRequest } from '@tripagent/tripagent-v1-sdk';

async function example() {
  console.log("🚀 Testing @tripagent/tripagent-v1-sdk SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: OperationsToken
    apiKey: "YOUR API KEY",
  });
  const api = new EvaluationsApi(config);

  const body = {
    // string | Backend-only operations credential injected from a secret store
    xOperationsToken: xOperationsToken_example,
    // string | Caller-generated key scoped to operation and authenticated actor
    idempotencyKey: idempotencyKey_example,
    // CreateEvaluationRunRequest
    createEvaluationRunRequest: ...,
  } satisfies StartEvaluationRunRequest;

  try {
    const data = await api.startEvaluationRun(body);
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
| **createEvaluationRunRequest** | [CreateEvaluationRunRequest](CreateEvaluationRunRequest.md) |  | |

### Return type

[**TaskAccepted**](TaskAccepted.md)

### Authorization

[OperationsToken](../README.md#OperationsToken)

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **202** | Evaluation replay accepted |  * Location -  <br>  |
| **400** | Malformed request or idempotency key |  -  |
| **401** | Authentication credential is missing or invalid |  -  |
| **409** | Idempotency, state, or version conflict |  -  |
| **422** | Domain or field validation failed |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)

