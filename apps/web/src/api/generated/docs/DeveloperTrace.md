
# DeveloperTrace


## Properties

Name | Type
------------ | -------------
`traceId` | string
`status` | string
`startedAt` | Date
`durationMs` | number
`spans` | [Array&lt;TraceSpan&gt;](TraceSpan.md)

## Example

```typescript
import type { DeveloperTrace } from '@tripagent/tripagent-v1-sdk'

// TODO: Update the object below with actual values
const example = {
  "traceId": null,
  "status": null,
  "startedAt": null,
  "durationMs": null,
  "spans": null,
} satisfies DeveloperTrace

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as DeveloperTrace
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


