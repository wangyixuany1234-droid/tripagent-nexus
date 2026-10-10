# TripsApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**createTrip**](TripsApi.md#createtripoperation) | **POST** /api/v1/trips | Create a trip project |
| [**getTrip**](TripsApi.md#gettrip) | **GET** /api/v1/trips/{tripId} | Get a trip project |
| [**saveTravelRequirement**](TripsApi.md#savetravelrequirementoperation) | **PUT** /api/v1/trips/{tripId}/requirements | Create or replace the normalized travel requirement |



## createTrip

> Trip createTrip(idempotencyKey, createTripRequest)

Create a trip project

### Example

```ts
import {
  Configuration,
  TripsApi,
} from '@tripagent/tripagent-v1-sdk';
import type { CreateTripOperationRequest } from '@tripagent/tripagent-v1-sdk';

async function example() {
  console.log("🚀 Testing @tripagent/tripagent-v1-sdk SDK...");
  const config = new Configuration({ 
    // Configure HTTP bearer authorization: BearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new TripsApi(config);

  const body = {
    // string | Caller-generated key scoped to operation and authenticated actor
    idempotencyKey: idempotencyKey_example,
    // CreateTripRequest
    createTripRequest: ...,
  } satisfies CreateTripOperationRequest;

  try {
    const data = await api.createTrip(body);
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
| **createTripRequest** | [CreateTripRequest](CreateTripRequest.md) |  | |

### Return type

[**Trip**](Trip.md)

### Authorization

[BearerAuth](../README.md#BearerAuth)

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **201** | Trip created |  * Location -  <br>  |
| **400** | Malformed request or idempotency key |  -  |
| **409** | Idempotency, state, or version conflict |  -  |
| **422** | Domain or field validation failed |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## getTrip

> Trip getTrip(tripId)

Get a trip project

### Example

```ts
import {
  Configuration,
  TripsApi,
} from '@tripagent/tripagent-v1-sdk';
import type { GetTripRequest } from '@tripagent/tripagent-v1-sdk';

async function example() {
  console.log("🚀 Testing @tripagent/tripagent-v1-sdk SDK...");
  const config = new Configuration({ 
    // Configure HTTP bearer authorization: BearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new TripsApi(config);

  const body = {
    // string
    tripId: 38400000-8cf0-11bd-b23e-10b96e4ef00d,
  } satisfies GetTripRequest;

  try {
    const data = await api.getTrip(body);
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

[**Trip**](Trip.md)

### Authorization

[BearerAuth](../README.md#BearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Trip |  -  |
| **400** | Malformed request or idempotency key |  -  |
| **404** | Resource not found in the authorized scope |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## saveTravelRequirement

> TravelRequirement saveTravelRequirement(idempotencyKey, tripId, saveTravelRequirementRequest)

Create or replace the normalized travel requirement

### Example

```ts
import {
  Configuration,
  TripsApi,
} from '@tripagent/tripagent-v1-sdk';
import type { SaveTravelRequirementOperationRequest } from '@tripagent/tripagent-v1-sdk';

async function example() {
  console.log("🚀 Testing @tripagent/tripagent-v1-sdk SDK...");
  const config = new Configuration({ 
    // Configure HTTP bearer authorization: BearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new TripsApi(config);

  const body = {
    // string | Caller-generated key scoped to operation and authenticated actor
    idempotencyKey: idempotencyKey_example,
    // string
    tripId: 38400000-8cf0-11bd-b23e-10b96e4ef00d,
    // SaveTravelRequirementRequest
    saveTravelRequirementRequest: ...,
  } satisfies SaveTravelRequirementOperationRequest;

  try {
    const data = await api.saveTravelRequirement(body);
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
| **tripId** | `string` |  | [Defaults to `undefined`] |
| **saveTravelRequirementRequest** | [SaveTravelRequirementRequest](SaveTravelRequirementRequest.md) |  | |

### Return type

[**TravelRequirement**](TravelRequirement.md)

### Authorization

[BearerAuth](../README.md#BearerAuth)

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Saved requirement |  -  |
| **400** | Malformed request or idempotency key |  -  |
| **404** | Resource not found in the authorized scope |  -  |
| **409** | Idempotency, state, or version conflict |  -  |
| **422** | Domain or field validation failed |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)

