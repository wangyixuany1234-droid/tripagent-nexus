
# ApiError


## Properties

Name | Type
------------ | -------------
`code` | string
`message` | string
`details` | [Array&lt;ErrorDetail&gt;](ErrorDetail.md)
`traceId` | string
`timestamp` | Date

## Example

```typescript
import type { ApiError } from '@tripagent/tripagent-v1-sdk'

// TODO: Update the object below with actual values
const example = {
  "code": null,
  "message": null,
  "details": null,
  "traceId": null,
  "timestamp": null,
} satisfies ApiError

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ApiError
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


