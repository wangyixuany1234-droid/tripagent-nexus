
# DeadLetterRecord


## Properties

Name | Type
------------ | -------------
`deadLetterId` | string
`originalEventId` | string
`originalTopic` | string
`originalEventType` | string
`eventVersion` | string
`correlationId` | string
`causationId` | string
`traceId` | string
`taskId` | string
`failureCode` | string
`failureReason` | string
`status` | [DeadLetterStatus](DeadLetterStatus.md)
`replayCount` | number
`replayEventId` | string
`replayTaskId` | string
`replayReason` | string
`createdAt` | Date
`updatedAt` | Date

## Example

```typescript
import type { DeadLetterRecord } from '@tripagent/tripagent-v1-sdk'

// TODO: Update the object below with actual values
const example = {
  "deadLetterId": null,
  "originalEventId": null,
  "originalTopic": null,
  "originalEventType": null,
  "eventVersion": null,
  "correlationId": null,
  "causationId": null,
  "traceId": null,
  "taskId": null,
  "failureCode": null,
  "failureReason": null,
  "status": null,
  "replayCount": null,
  "replayEventId": null,
  "replayTaskId": null,
  "replayReason": null,
  "createdAt": null,
  "updatedAt": null,
} satisfies DeadLetterRecord

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as DeadLetterRecord
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


