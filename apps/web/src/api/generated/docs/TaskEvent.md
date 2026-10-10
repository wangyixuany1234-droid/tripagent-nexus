
# TaskEvent


## Properties

Name | Type
------------ | -------------
`eventId` | string
`taskId` | string
`stage` | string
`progress` | number
`message` | string
`timestamp` | Date

## Example

```typescript
import type { TaskEvent } from '@tripagent/tripagent-v1-sdk'

// TODO: Update the object below with actual values
const example = {
  "eventId": null,
  "taskId": null,
  "stage": null,
  "progress": null,
  "message": null,
  "timestamp": null,
} satisfies TaskEvent

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as TaskEvent
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


