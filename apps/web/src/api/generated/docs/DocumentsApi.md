# DocumentsApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**correctParsedBooking**](DocumentsApi.md#correctparsedbooking) | **PATCH** /api/v1/documents/{documentId}/bookings/{bookingId} | Correct extracted booking fields and append an immutable audit record |
| [**getParsedDocument**](DocumentsApi.md#getparseddocument) | **GET** /api/v1/documents/{documentId} | Get document parsing state and parsed bookings |
| [**parseDocument**](DocumentsApi.md#parsedocument) | **POST** /api/v1/documents/parse | Accept a document and queue asynchronous parsing |
| [**retryDocumentParsing**](DocumentsApi.md#retrydocumentparsing) | **POST** /api/v1/documents/{documentId}/retry | Retry parsing for a failed document without uploading its content again |



## correctParsedBooking

> ParsedBooking correctParsedBooking(idempotencyKey, documentId, bookingId, bookingCorrectionRequest, xActorId)

Correct extracted booking fields and append an immutable audit record

### Example

```ts
import {
  Configuration,
  DocumentsApi,
} from '@tripagent/tripagent-v1-sdk';
import type { CorrectParsedBookingRequest } from '@tripagent/tripagent-v1-sdk';

async function example() {
  console.log("🚀 Testing @tripagent/tripagent-v1-sdk SDK...");
  const config = new Configuration({ 
    // Configure HTTP bearer authorization: BearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new DocumentsApi(config);

  const body = {
    // string | Caller-generated key scoped to operation and authenticated actor
    idempotencyKey: idempotencyKey_example,
    // string
    documentId: 38400000-8cf0-11bd-b23e-10b96e4ef00d,
    // string
    bookingId: 38400000-8cf0-11bd-b23e-10b96e4ef00d,
    // BookingCorrectionRequest
    bookingCorrectionRequest: ...,
    // string | Legacy compatibility field; ignored for authorization when JWT authentication is enabled (optional)
    xActorId: xActorId_example,
  } satisfies CorrectParsedBookingRequest;

  try {
    const data = await api.correctParsedBooking(body);
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
| **documentId** | `string` |  | [Defaults to `undefined`] |
| **bookingId** | `string` |  | [Defaults to `undefined`] |
| **bookingCorrectionRequest** | [BookingCorrectionRequest](BookingCorrectionRequest.md) |  | |
| **xActorId** | `string` | Legacy compatibility field; ignored for authorization when JWT authentication is enabled | [Optional] [Defaults to `undefined`] |

### Return type

[**ParsedBooking**](ParsedBooking.md)

### Authorization

[BearerAuth](../README.md#BearerAuth)

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Corrected booking |  -  |
| **400** | Malformed request or idempotency key |  -  |
| **404** | Resource not found in the authorized scope |  -  |
| **409** | Idempotency, state, or version conflict |  -  |
| **422** | Domain or field validation failed |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## getParsedDocument

> ParsedDocument getParsedDocument(documentId)

Get document parsing state and parsed bookings

### Example

```ts
import {
  Configuration,
  DocumentsApi,
} from '@tripagent/tripagent-v1-sdk';
import type { GetParsedDocumentRequest } from '@tripagent/tripagent-v1-sdk';

async function example() {
  console.log("🚀 Testing @tripagent/tripagent-v1-sdk SDK...");
  const config = new Configuration({ 
    // Configure HTTP bearer authorization: BearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new DocumentsApi(config);

  const body = {
    // string
    documentId: 38400000-8cf0-11bd-b23e-10b96e4ef00d,
  } satisfies GetParsedDocumentRequest;

  try {
    const data = await api.getParsedDocument(body);
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
| **documentId** | `string` |  | [Defaults to `undefined`] |

### Return type

[**ParsedDocument**](ParsedDocument.md)

### Authorization

[BearerAuth](../README.md#BearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Parsed document |  -  |
| **400** | Malformed request or idempotency key |  -  |
| **404** | Resource not found in the authorized scope |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## parseDocument

> TaskAccepted parseDocument(idempotencyKey, tripId, file)

Accept a document and queue asynchronous parsing

### Example

```ts
import {
  Configuration,
  DocumentsApi,
} from '@tripagent/tripagent-v1-sdk';
import type { ParseDocumentRequest } from '@tripagent/tripagent-v1-sdk';

async function example() {
  console.log("🚀 Testing @tripagent/tripagent-v1-sdk SDK...");
  const config = new Configuration({ 
    // Configure HTTP bearer authorization: BearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new DocumentsApi(config);

  const body = {
    // string | Caller-generated key scoped to operation and authenticated actor
    idempotencyKey: idempotencyKey_example,
    // string
    tripId: 38400000-8cf0-11bd-b23e-10b96e4ef00d,
    // Blob | PNG, JPEG, WEBP, or PDF within configured byte, pixel, and page limits
    file: BINARY_DATA_HERE,
  } satisfies ParseDocumentRequest;

  try {
    const data = await api.parseDocument(body);
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
| **file** | `Blob` | PNG, JPEG, WEBP, or PDF within configured byte, pixel, and page limits | [Defaults to `undefined`] |

### Return type

[**TaskAccepted**](TaskAccepted.md)

### Authorization

[BearerAuth](../README.md#BearerAuth)

### HTTP request headers

- **Content-Type**: `multipart/form-data`
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **202** | Document parsing accepted |  * Location -  <br>  |
| **400** | Malformed request or idempotency key |  -  |
| **404** | Resource not found in the authorized scope |  -  |
| **409** | Idempotency, state, or version conflict |  -  |
| **413** | Uploaded document exceeds the configured limit |  -  |
| **415** | Uploaded MIME type or extension is not allowed |  -  |
| **422** | Domain or field validation failed |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## retryDocumentParsing

> TaskAccepted retryDocumentParsing(idempotencyKey, documentId)

Retry parsing for a failed document without uploading its content again

### Example

```ts
import {
  Configuration,
  DocumentsApi,
} from '@tripagent/tripagent-v1-sdk';
import type { RetryDocumentParsingRequest } from '@tripagent/tripagent-v1-sdk';

async function example() {
  console.log("🚀 Testing @tripagent/tripagent-v1-sdk SDK...");
  const config = new Configuration({ 
    // Configure HTTP bearer authorization: BearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new DocumentsApi(config);

  const body = {
    // string | Caller-generated key scoped to operation and authenticated actor
    idempotencyKey: idempotencyKey_example,
    // string
    documentId: 38400000-8cf0-11bd-b23e-10b96e4ef00d,
  } satisfies RetryDocumentParsingRequest;

  try {
    const data = await api.retryDocumentParsing(body);
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
| **documentId** | `string` |  | [Defaults to `undefined`] |

### Return type

[**TaskAccepted**](TaskAccepted.md)

### Authorization

[BearerAuth](../README.md#BearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **202** | Document parsing retry accepted |  * Location -  <br>  |
| **400** | Malformed request or idempotency key |  -  |
| **404** | Resource not found in the authorized scope |  -  |
| **409** | Idempotency, state, or version conflict |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)

