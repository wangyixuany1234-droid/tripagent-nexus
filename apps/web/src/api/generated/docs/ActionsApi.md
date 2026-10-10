# ActionsApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**confirmGuardedAction**](ActionsApi.md#confirmguardedactionoperation) | **POST** /api/v1/actions/{actionId}/confirm | Confirm an unexpired candidate after acknowledging its risk and exact cost |
| [**getGuardedAction**](ActionsApi.md#getguardedaction) | **GET** /api/v1/actions/{actionId} | Get a candidate action and its immutable audit trail |
| [**proposeGuardedAction**](ActionsApi.md#proposeguardedaction) | **POST** /api/v1/actions | Create a non-executable candidate action awaiting explicit user confirmation |
| [**rejectGuardedAction**](ActionsApi.md#rejectguardedactionoperation) | **POST** /api/v1/actions/{actionId}/reject | Reject a candidate action and append an immutable audit record |



## confirmGuardedAction

> GuardedAction confirmGuardedAction(idempotencyKey, actionId, confirmGuardedActionRequest, xActorId)

Confirm an unexpired candidate after acknowledging its risk and exact cost

### Example

```ts
import {
  Configuration,
  ActionsApi,
} from '@tripagent/tripagent-v1-sdk';
import type { ConfirmGuardedActionOperationRequest } from '@tripagent/tripagent-v1-sdk';

async function example() {
  console.log("🚀 Testing @tripagent/tripagent-v1-sdk SDK...");
  const config = new Configuration({ 
    // Configure HTTP bearer authorization: BearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new ActionsApi(config);

  const body = {
    // string | Caller-generated key scoped to operation and authenticated actor
    idempotencyKey: idempotencyKey_example,
    // string
    actionId: 38400000-8cf0-11bd-b23e-10b96e4ef00d,
    // ConfirmGuardedActionRequest
    confirmGuardedActionRequest: ...,
    // string | Legacy compatibility field; ignored for authorization when JWT authentication is enabled (optional)
    xActorId: xActorId_example,
  } satisfies ConfirmGuardedActionOperationRequest;

  try {
    const data = await api.confirmGuardedAction(body);
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
| **actionId** | `string` |  | [Defaults to `undefined`] |
| **confirmGuardedActionRequest** | [ConfirmGuardedActionRequest](ConfirmGuardedActionRequest.md) |  | |
| **xActorId** | `string` | Legacy compatibility field; ignored for authorization when JWT authentication is enabled | [Optional] [Defaults to `undefined`] |

### Return type

[**GuardedAction**](GuardedAction.md)

### Authorization

[BearerAuth](../README.md#BearerAuth)

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Confirmed action. Confirmation alone does not call a provider. |  -  |
| **400** | Malformed request or idempotency key |  -  |
| **404** | Resource not found in the authorized scope |  -  |
| **409** | Idempotency, state, or version conflict |  -  |
| **422** | Domain or field validation failed |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## getGuardedAction

> GuardedAction getGuardedAction(actionId, xActorId)

Get a candidate action and its immutable audit trail

### Example

```ts
import {
  Configuration,
  ActionsApi,
} from '@tripagent/tripagent-v1-sdk';
import type { GetGuardedActionRequest } from '@tripagent/tripagent-v1-sdk';

async function example() {
  console.log("🚀 Testing @tripagent/tripagent-v1-sdk SDK...");
  const config = new Configuration({ 
    // Configure HTTP bearer authorization: BearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new ActionsApi(config);

  const body = {
    // string
    actionId: 38400000-8cf0-11bd-b23e-10b96e4ef00d,
    // string | Legacy compatibility field; ignored for authorization when JWT authentication is enabled (optional)
    xActorId: xActorId_example,
  } satisfies GetGuardedActionRequest;

  try {
    const data = await api.getGuardedAction(body);
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
| **actionId** | `string` |  | [Defaults to `undefined`] |
| **xActorId** | `string` | Legacy compatibility field; ignored for authorization when JWT authentication is enabled | [Optional] [Defaults to `undefined`] |

### Return type

[**GuardedAction**](GuardedAction.md)

### Authorization

[BearerAuth](../README.md#BearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Guarded action |  -  |
| **400** | Malformed request or idempotency key |  -  |
| **404** | Resource not found in the authorized scope |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## proposeGuardedAction

> GuardedAction proposeGuardedAction(idempotencyKey, createGuardedActionRequest, xActorId)

Create a non-executable candidate action awaiting explicit user confirmation

### Example

```ts
import {
  Configuration,
  ActionsApi,
} from '@tripagent/tripagent-v1-sdk';
import type { ProposeGuardedActionRequest } from '@tripagent/tripagent-v1-sdk';

async function example() {
  console.log("🚀 Testing @tripagent/tripagent-v1-sdk SDK...");
  const config = new Configuration({ 
    // Configure HTTP bearer authorization: BearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new ActionsApi(config);

  const body = {
    // string | Caller-generated key scoped to operation and authenticated actor
    idempotencyKey: idempotencyKey_example,
    // CreateGuardedActionRequest
    createGuardedActionRequest: ...,
    // string | Legacy compatibility field; ignored for authorization when JWT authentication is enabled (optional)
    xActorId: xActorId_example,
  } satisfies ProposeGuardedActionRequest;

  try {
    const data = await api.proposeGuardedAction(body);
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
| **createGuardedActionRequest** | [CreateGuardedActionRequest](CreateGuardedActionRequest.md) |  | |
| **xActorId** | `string` | Legacy compatibility field; ignored for authorization when JWT authentication is enabled | [Optional] [Defaults to `undefined`] |

### Return type

[**GuardedAction**](GuardedAction.md)

### Authorization

[BearerAuth](../README.md#BearerAuth)

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **201** | Candidate action created |  * Location -  <br>  |
| **400** | Malformed request or idempotency key |  -  |
| **409** | Idempotency, state, or version conflict |  -  |
| **422** | Domain or field validation failed |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## rejectGuardedAction

> GuardedAction rejectGuardedAction(idempotencyKey, actionId, rejectGuardedActionRequest, xActorId)

Reject a candidate action and append an immutable audit record

### Example

```ts
import {
  Configuration,
  ActionsApi,
} from '@tripagent/tripagent-v1-sdk';
import type { RejectGuardedActionOperationRequest } from '@tripagent/tripagent-v1-sdk';

async function example() {
  console.log("🚀 Testing @tripagent/tripagent-v1-sdk SDK...");
  const config = new Configuration({ 
    // Configure HTTP bearer authorization: BearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new ActionsApi(config);

  const body = {
    // string | Caller-generated key scoped to operation and authenticated actor
    idempotencyKey: idempotencyKey_example,
    // string
    actionId: 38400000-8cf0-11bd-b23e-10b96e4ef00d,
    // RejectGuardedActionRequest
    rejectGuardedActionRequest: ...,
    // string | Legacy compatibility field; ignored for authorization when JWT authentication is enabled (optional)
    xActorId: xActorId_example,
  } satisfies RejectGuardedActionOperationRequest;

  try {
    const data = await api.rejectGuardedAction(body);
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
| **actionId** | `string` |  | [Defaults to `undefined`] |
| **rejectGuardedActionRequest** | [RejectGuardedActionRequest](RejectGuardedActionRequest.md) |  | |
| **xActorId** | `string` | Legacy compatibility field; ignored for authorization when JWT authentication is enabled | [Optional] [Defaults to `undefined`] |

### Return type

[**GuardedAction**](GuardedAction.md)

### Authorization

[BearerAuth](../README.md#BearerAuth)

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Rejected action |  -  |
| **400** | Malformed request or idempotency key |  -  |
| **404** | Resource not found in the authorized scope |  -  |
| **409** | Idempotency, state, or version conflict |  -  |
| **422** | Domain or field validation failed |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)

