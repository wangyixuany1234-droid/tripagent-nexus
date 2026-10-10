# PlansApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**compareTravelPlanVersions**](PlansApi.md#comparetravelplanversions) | **GET** /api/v1/plans/{planId}/compare/{otherPlanId} | Compare two immutable versions from the same plan family |
| [**confirmTravelPlan**](PlansApi.md#confirmtravelplan) | **POST** /api/v1/plans/{planId}/confirm | Confirm a generated travel plan without executing provider actions |
| [**createTravelPlan**](PlansApi.md#createtravelplanoperation) | **POST** /api/v1/plans | Reserve a plan resource and queue asynchronous generation |
| [**getTravelPlan**](PlansApi.md#gettravelplan) | **GET** /api/v1/plans/{planId} | Get one travel plan |
| [**listTravelPlans**](PlansApi.md#listtravelplans) | **GET** /api/v1/trips/{tripId}/plans | List plans belonging to a trip |
| [**replanTravelPlan**](PlansApi.md#replantravelplan) | **POST** /api/v1/plans/{planId}/replan | Create a successor plan version and queue regeneration |



## compareTravelPlanVersions

> PlanComparison compareTravelPlanVersions(planId, otherPlanId)

Compare two immutable versions from the same plan family

### Example

```ts
import {
  Configuration,
  PlansApi,
} from '@tripagent/tripagent-v1-sdk';
import type { CompareTravelPlanVersionsRequest } from '@tripagent/tripagent-v1-sdk';

async function example() {
  console.log("🚀 Testing @tripagent/tripagent-v1-sdk SDK...");
  const config = new Configuration({ 
    // Configure HTTP bearer authorization: BearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new PlansApi(config);

  const body = {
    // string
    planId: 38400000-8cf0-11bd-b23e-10b96e4ef00d,
    // string
    otherPlanId: 38400000-8cf0-11bd-b23e-10b96e4ef00d,
  } satisfies CompareTravelPlanVersionsRequest;

  try {
    const data = await api.compareTravelPlanVersions(body);
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
| **planId** | `string` |  | [Defaults to `undefined`] |
| **otherPlanId** | `string` |  | [Defaults to `undefined`] |

### Return type

[**PlanComparison**](PlanComparison.md)

### Authorization

[BearerAuth](../README.md#BearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Version comparison |  -  |
| **400** | Malformed request or idempotency key |  -  |
| **404** | Resource not found in the authorized scope |  -  |
| **422** | Domain or field validation failed |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## confirmTravelPlan

> TravelPlan confirmTravelPlan(idempotencyKey, planId, confirmPlanRequest)

Confirm a generated travel plan without executing provider actions

### Example

```ts
import {
  Configuration,
  PlansApi,
} from '@tripagent/tripagent-v1-sdk';
import type { ConfirmTravelPlanRequest } from '@tripagent/tripagent-v1-sdk';

async function example() {
  console.log("🚀 Testing @tripagent/tripagent-v1-sdk SDK...");
  const config = new Configuration({ 
    // Configure HTTP bearer authorization: BearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new PlansApi(config);

  const body = {
    // string | Caller-generated key scoped to operation and authenticated actor
    idempotencyKey: idempotencyKey_example,
    // string
    planId: 38400000-8cf0-11bd-b23e-10b96e4ef00d,
    // ConfirmPlanRequest
    confirmPlanRequest: ...,
  } satisfies ConfirmTravelPlanRequest;

  try {
    const data = await api.confirmTravelPlan(body);
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
| **planId** | `string` |  | [Defaults to `undefined`] |
| **confirmPlanRequest** | [ConfirmPlanRequest](ConfirmPlanRequest.md) |  | |

### Return type

[**TravelPlan**](TravelPlan.md)

### Authorization

[BearerAuth](../README.md#BearerAuth)

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Confirmed plan |  -  |
| **400** | Malformed request or idempotency key |  -  |
| **404** | Resource not found in the authorized scope |  -  |
| **409** | Idempotency, state, or version conflict |  -  |
| **422** | Domain or field validation failed |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## createTravelPlan

> TaskAccepted createTravelPlan(idempotencyKey, createTravelPlanRequest)

Reserve a plan resource and queue asynchronous generation

### Example

```ts
import {
  Configuration,
  PlansApi,
} from '@tripagent/tripagent-v1-sdk';
import type { CreateTravelPlanOperationRequest } from '@tripagent/tripagent-v1-sdk';

async function example() {
  console.log("🚀 Testing @tripagent/tripagent-v1-sdk SDK...");
  const config = new Configuration({ 
    // Configure HTTP bearer authorization: BearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new PlansApi(config);

  const body = {
    // string | Caller-generated key scoped to operation and authenticated actor
    idempotencyKey: idempotencyKey_example,
    // CreateTravelPlanRequest
    createTravelPlanRequest: ...,
  } satisfies CreateTravelPlanOperationRequest;

  try {
    const data = await api.createTravelPlan(body);
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
| **createTravelPlanRequest** | [CreateTravelPlanRequest](CreateTravelPlanRequest.md) |  | |

### Return type

[**TaskAccepted**](TaskAccepted.md)

### Authorization

[BearerAuth](../README.md#BearerAuth)

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **202** | Plan generation accepted |  * Location -  <br>  |
| **400** | Malformed request or idempotency key |  -  |
| **404** | Resource not found in the authorized scope |  -  |
| **409** | Idempotency, state, or version conflict |  -  |
| **422** | Domain or field validation failed |  -  |
| **429** | Operations rate limit exceeded |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## getTravelPlan

> TravelPlan getTravelPlan(planId)

Get one travel plan

### Example

```ts
import {
  Configuration,
  PlansApi,
} from '@tripagent/tripagent-v1-sdk';
import type { GetTravelPlanRequest } from '@tripagent/tripagent-v1-sdk';

async function example() {
  console.log("🚀 Testing @tripagent/tripagent-v1-sdk SDK...");
  const config = new Configuration({ 
    // Configure HTTP bearer authorization: BearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new PlansApi(config);

  const body = {
    // string
    planId: 38400000-8cf0-11bd-b23e-10b96e4ef00d,
  } satisfies GetTravelPlanRequest;

  try {
    const data = await api.getTravelPlan(body);
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
| **planId** | `string` |  | [Defaults to `undefined`] |

### Return type

[**TravelPlan**](TravelPlan.md)

### Authorization

[BearerAuth](../README.md#BearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Travel plan |  -  |
| **400** | Malformed request or idempotency key |  -  |
| **404** | Resource not found in the authorized scope |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## listTravelPlans

> TravelPlanList listTravelPlans(tripId)

List plans belonging to a trip

### Example

```ts
import {
  Configuration,
  PlansApi,
} from '@tripagent/tripagent-v1-sdk';
import type { ListTravelPlansRequest } from '@tripagent/tripagent-v1-sdk';

async function example() {
  console.log("🚀 Testing @tripagent/tripagent-v1-sdk SDK...");
  const config = new Configuration({ 
    // Configure HTTP bearer authorization: BearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new PlansApi(config);

  const body = {
    // string
    tripId: 38400000-8cf0-11bd-b23e-10b96e4ef00d,
  } satisfies ListTravelPlansRequest;

  try {
    const data = await api.listTravelPlans(body);
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

[**TravelPlanList**](TravelPlanList.md)

### Authorization

[BearerAuth](../README.md#BearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Plan list |  -  |
| **400** | Malformed request or idempotency key |  -  |
| **404** | Resource not found in the authorized scope |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## replanTravelPlan

> TaskAccepted replanTravelPlan(idempotencyKey, planId, replanRequest)

Create a successor plan version and queue regeneration

### Example

```ts
import {
  Configuration,
  PlansApi,
} from '@tripagent/tripagent-v1-sdk';
import type { ReplanTravelPlanRequest } from '@tripagent/tripagent-v1-sdk';

async function example() {
  console.log("🚀 Testing @tripagent/tripagent-v1-sdk SDK...");
  const config = new Configuration({ 
    // Configure HTTP bearer authorization: BearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new PlansApi(config);

  const body = {
    // string | Caller-generated key scoped to operation and authenticated actor
    idempotencyKey: idempotencyKey_example,
    // string
    planId: 38400000-8cf0-11bd-b23e-10b96e4ef00d,
    // ReplanRequest
    replanRequest: ...,
  } satisfies ReplanTravelPlanRequest;

  try {
    const data = await api.replanTravelPlan(body);
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
| **planId** | `string` |  | [Defaults to `undefined`] |
| **replanRequest** | [ReplanRequest](ReplanRequest.md) |  | |

### Return type

[**TaskAccepted**](TaskAccepted.md)

### Authorization

[BearerAuth](../README.md#BearerAuth)

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **202** | Replanning accepted |  * Location -  <br>  |
| **400** | Malformed request or idempotency key |  -  |
| **404** | Resource not found in the authorized scope |  -  |
| **409** | Idempotency, state, or version conflict |  -  |
| **422** | Domain or field validation failed |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)

