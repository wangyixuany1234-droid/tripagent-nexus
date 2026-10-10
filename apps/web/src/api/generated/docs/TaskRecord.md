
# TaskRecord


## Properties

Name | Type
------------ | -------------
`taskId` | string
`type` | [TaskType](TaskType.md)
`resourceId` | string
`status` | [TaskStatus](TaskStatus.md)
`stage` | string
`progress` | number
`message` | string
`traceId` | string
`resultResourceIds` | Set&lt;string&gt;
`failureCode` | string
`failureReason` | string
`retryable` | boolean
`recoveryAttempts` | number
`degradedComponents` | Set&lt;string&gt;
`retryOfTaskId` | string
`createdAt` | Date
`updatedAt` | Date

## Example

```typescript
import type { TaskRecord } from '@tripagent/tripagent-v1-sdk'

// TODO: Update the object below with actual values
const example = {
  "taskId": null,
  "type": null,
  "resourceId": null,
  "status": null,
  "stage": null,
  "progress": null,
  "message": null,
  "traceId": null,
  "resultResourceIds": null,
  "failureCode": null,
  "failureReason": null,
  "retryable": null,
  "recoveryAttempts": null,
  "degradedComponents": null,
  "retryOfTaskId": null,
  "createdAt": null,
  "updatedAt": null,
} satisfies TaskRecord

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as TaskRecord
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


