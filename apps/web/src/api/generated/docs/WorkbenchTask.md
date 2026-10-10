
# WorkbenchTask


## Properties

Name | Type
------------ | -------------
`taskId` | string
`tripId` | string
`resultPlanId` | string
`title` | string
`destination` | string
`startDate` | Date
`endDate` | Date
`type` | [TaskType](TaskType.md)
`status` | [TaskStatus](TaskStatus.md)
`progress` | number
`createdAt` | Date
`updatedAt` | Date

## Example

```typescript
import type { WorkbenchTask } from '@tripagent/tripagent-v1-sdk'

// TODO: Update the object below with actual values
const example = {
  "taskId": null,
  "tripId": null,
  "resultPlanId": null,
  "title": null,
  "destination": null,
  "startDate": null,
  "endDate": null,
  "type": null,
  "status": null,
  "progress": null,
  "createdAt": null,
  "updatedAt": null,
} satisfies WorkbenchTask

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as WorkbenchTask
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


