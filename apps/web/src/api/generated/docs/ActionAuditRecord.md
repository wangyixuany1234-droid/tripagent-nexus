
# ActionAuditRecord


## Properties

Name | Type
------------ | -------------
`auditId` | string
`actionId` | string
`actorId` | string
`eventType` | string
`reason` | string
`occurredAt` | Date
`traceId` | string

## Example

```typescript
import type { ActionAuditRecord } from '@tripagent/tripagent-v1-sdk'

// TODO: Update the object below with actual values
const example = {
  "auditId": null,
  "actionId": null,
  "actorId": null,
  "eventType": null,
  "reason": null,
  "occurredAt": null,
  "traceId": null,
} satisfies ActionAuditRecord

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ActionAuditRecord
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


