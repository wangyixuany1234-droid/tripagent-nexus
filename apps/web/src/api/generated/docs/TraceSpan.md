
# TraceSpan


## Properties

Name | Type
------------ | -------------
`spanId` | string
`parentSpanId` | string
`category` | [TraceSpanCategory](TraceSpanCategory.md)
`name` | string
`status` | string
`startedAt` | Date
`durationMs` | number
`attributes` | { [key: string]: string; }

## Example

```typescript
import type { TraceSpan } from '@tripagent/tripagent-v1-sdk'

// TODO: Update the object below with actual values
const example = {
  "spanId": null,
  "parentSpanId": null,
  "category": null,
  "name": null,
  "status": null,
  "startedAt": null,
  "durationMs": null,
  "attributes": null,
} satisfies TraceSpan

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as TraceSpan
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


