# PreferencesApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**createUserPreference**](PreferencesApi.md#createuserpreference) | **POST** /api/v1/users/{userId}/preferences | Create a user-provided preference |
| [**deleteUserPreference**](PreferencesApi.md#deleteuserpreference) | **DELETE** /api/v1/users/{userId}/preferences/{preferenceId} | Delete a preference and request backend memory cleanup |
| [**listUserPreferences**](PreferencesApi.md#listuserpreferences) | **GET** /api/v1/users/{userId}/preferences | List active and rejected preferences controlled by the user |
| [**rejectInferredPreference**](PreferencesApi.md#rejectinferredpreference) | **POST** /api/v1/users/{userId}/preferences/{preferenceId}/reject | Reject an inferred preference and request backend memory cleanup |
| [**updateUserPreference**](PreferencesApi.md#updateuserpreference) | **PUT** /api/v1/users/{userId}/preferences/{preferenceId} | Update a preference value |



## createUserPreference

> UserPreference createUserPreference(idempotencyKey, userId, saveUserPreferenceRequest)

Create a user-provided preference

### Example

```ts
import {
  Configuration,
  PreferencesApi,
} from '@tripagent/tripagent-v1-sdk';
import type { CreateUserPreferenceRequest } from '@tripagent/tripagent-v1-sdk';

async function example() {
  console.log("🚀 Testing @tripagent/tripagent-v1-sdk SDK...");
  const config = new Configuration({ 
    // Configure HTTP bearer authorization: BearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new PreferencesApi(config);

  const body = {
    // string | Caller-generated key scoped to operation and authenticated actor
    idempotencyKey: idempotencyKey_example,
    // string
    userId: 38400000-8cf0-11bd-b23e-10b96e4ef00d,
    // SaveUserPreferenceRequest
    saveUserPreferenceRequest: ...,
  } satisfies CreateUserPreferenceRequest;

  try {
    const data = await api.createUserPreference(body);
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
| **userId** | `string` |  | [Defaults to `undefined`] |
| **saveUserPreferenceRequest** | [SaveUserPreferenceRequest](SaveUserPreferenceRequest.md) |  | |

### Return type

[**UserPreference**](UserPreference.md)

### Authorization

[BearerAuth](../README.md#BearerAuth)

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **201** | Preference created |  -  |
| **400** | Malformed request or idempotency key |  -  |
| **409** | Idempotency, state, or version conflict |  -  |
| **422** | Domain or field validation failed |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## deleteUserPreference

> deleteUserPreference(idempotencyKey, userId, preferenceId)

Delete a preference and request backend memory cleanup

### Example

```ts
import {
  Configuration,
  PreferencesApi,
} from '@tripagent/tripagent-v1-sdk';
import type { DeleteUserPreferenceRequest } from '@tripagent/tripagent-v1-sdk';

async function example() {
  console.log("🚀 Testing @tripagent/tripagent-v1-sdk SDK...");
  const config = new Configuration({ 
    // Configure HTTP bearer authorization: BearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new PreferencesApi(config);

  const body = {
    // string | Caller-generated key scoped to operation and authenticated actor
    idempotencyKey: idempotencyKey_example,
    // string
    userId: 38400000-8cf0-11bd-b23e-10b96e4ef00d,
    // string
    preferenceId: 38400000-8cf0-11bd-b23e-10b96e4ef00d,
  } satisfies DeleteUserPreferenceRequest;

  try {
    const data = await api.deleteUserPreference(body);
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
| **userId** | `string` |  | [Defaults to `undefined`] |
| **preferenceId** | `string` |  | [Defaults to `undefined`] |

### Return type

`void` (Empty response body)

### Authorization

[BearerAuth](../README.md#BearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **204** | Preference deleted and cleanup requested |  -  |
| **400** | Malformed request or idempotency key |  -  |
| **404** | Resource not found in the authorized scope |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## listUserPreferences

> UserPreferenceList listUserPreferences(userId)

List active and rejected preferences controlled by the user

### Example

```ts
import {
  Configuration,
  PreferencesApi,
} from '@tripagent/tripagent-v1-sdk';
import type { ListUserPreferencesRequest } from '@tripagent/tripagent-v1-sdk';

async function example() {
  console.log("🚀 Testing @tripagent/tripagent-v1-sdk SDK...");
  const config = new Configuration({ 
    // Configure HTTP bearer authorization: BearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new PreferencesApi(config);

  const body = {
    // string
    userId: 38400000-8cf0-11bd-b23e-10b96e4ef00d,
  } satisfies ListUserPreferencesRequest;

  try {
    const data = await api.listUserPreferences(body);
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

### Return type

[**UserPreferenceList**](UserPreferenceList.md)

### Authorization

[BearerAuth](../README.md#BearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Preference list |  -  |
| **400** | Malformed request or idempotency key |  -  |
| **404** | Resource not found in the authorized scope |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## rejectInferredPreference

> UserPreference rejectInferredPreference(idempotencyKey, userId, preferenceId)

Reject an inferred preference and request backend memory cleanup

### Example

```ts
import {
  Configuration,
  PreferencesApi,
} from '@tripagent/tripagent-v1-sdk';
import type { RejectInferredPreferenceRequest } from '@tripagent/tripagent-v1-sdk';

async function example() {
  console.log("🚀 Testing @tripagent/tripagent-v1-sdk SDK...");
  const config = new Configuration({ 
    // Configure HTTP bearer authorization: BearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new PreferencesApi(config);

  const body = {
    // string | Caller-generated key scoped to operation and authenticated actor
    idempotencyKey: idempotencyKey_example,
    // string
    userId: 38400000-8cf0-11bd-b23e-10b96e4ef00d,
    // string
    preferenceId: 38400000-8cf0-11bd-b23e-10b96e4ef00d,
  } satisfies RejectInferredPreferenceRequest;

  try {
    const data = await api.rejectInferredPreference(body);
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
| **userId** | `string` |  | [Defaults to `undefined`] |
| **preferenceId** | `string` |  | [Defaults to `undefined`] |

### Return type

[**UserPreference**](UserPreference.md)

### Authorization

[BearerAuth](../README.md#BearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Inferred preference rejected |  -  |
| **400** | Malformed request or idempotency key |  -  |
| **404** | Resource not found in the authorized scope |  -  |
| **409** | Idempotency, state, or version conflict |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## updateUserPreference

> UserPreference updateUserPreference(idempotencyKey, userId, preferenceId, saveUserPreferenceRequest)

Update a preference value

### Example

```ts
import {
  Configuration,
  PreferencesApi,
} from '@tripagent/tripagent-v1-sdk';
import type { UpdateUserPreferenceRequest } from '@tripagent/tripagent-v1-sdk';

async function example() {
  console.log("🚀 Testing @tripagent/tripagent-v1-sdk SDK...");
  const config = new Configuration({ 
    // Configure HTTP bearer authorization: BearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new PreferencesApi(config);

  const body = {
    // string | Caller-generated key scoped to operation and authenticated actor
    idempotencyKey: idempotencyKey_example,
    // string
    userId: 38400000-8cf0-11bd-b23e-10b96e4ef00d,
    // string
    preferenceId: 38400000-8cf0-11bd-b23e-10b96e4ef00d,
    // SaveUserPreferenceRequest
    saveUserPreferenceRequest: ...,
  } satisfies UpdateUserPreferenceRequest;

  try {
    const data = await api.updateUserPreference(body);
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
| **userId** | `string` |  | [Defaults to `undefined`] |
| **preferenceId** | `string` |  | [Defaults to `undefined`] |
| **saveUserPreferenceRequest** | [SaveUserPreferenceRequest](SaveUserPreferenceRequest.md) |  | |

### Return type

[**UserPreference**](UserPreference.md)

### Authorization

[BearerAuth](../README.md#BearerAuth)

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Preference updated |  -  |
| **400** | Malformed request or idempotency key |  -  |
| **404** | Resource not found in the authorized scope |  -  |
| **409** | Idempotency, state, or version conflict |  -  |
| **422** | Domain or field validation failed |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)

