# MapsApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**getMapTile**](MapsApi.md#getmaptile) | **GET** /api/v1/maps/tiles/{zoom}/{x}/{y}.png | Get a cached world map tile through the backend allowlist |



## getMapTile

> Blob getMapTile(zoom, x, y)

Get a cached world map tile through the backend allowlist

### Example

```ts
import {
  Configuration,
  MapsApi,
} from '@tripagent/tripagent-v1-sdk';
import type { GetMapTileRequest } from '@tripagent/tripagent-v1-sdk';

async function example() {
  console.log("🚀 Testing @tripagent/tripagent-v1-sdk SDK...");
  const api = new MapsApi();

  const body = {
    // number
    zoom: 56,
    // number
    x: 56,
    // number
    y: 56,
  } satisfies GetMapTileRequest;

  try {
    const data = await api.getMapTile(body);
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
| **zoom** | `number` |  | [Defaults to `undefined`] |
| **x** | `number` |  | [Defaults to `undefined`] |
| **y** | `number` |  | [Defaults to `undefined`] |

### Return type

**Blob**

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `image/png`, `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | PNG map tile |  * Cache-Control - Browser cache policy inherited from the configured tile provider <br>  * ETag - Provider entity tag when available <br>  |
| **400** | Malformed request or idempotency key |  -  |
| **502** | Configured upstream provider failed or returned an invalid response |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)

