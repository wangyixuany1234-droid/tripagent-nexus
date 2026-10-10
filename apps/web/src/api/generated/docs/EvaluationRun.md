
# EvaluationRun


## Properties

Name | Type
------------ | -------------
`runId` | string
`taskId` | string
`datasetVersion` | string
`status` | [EvaluationRunStatus](EvaluationRunStatus.md)
`sampleSize` | number
`metrics` | [EvaluationMetrics](EvaluationMetrics.md)
`cases` | [Array&lt;EvaluationCaseResult&gt;](EvaluationCaseResult.md)
`environment` | [EvaluationEnvironment](EvaluationEnvironment.md)
`traceId` | string
`startedAt` | Date
`completedAt` | Date
`createdAt` | Date
`updatedAt` | Date

## Example

```typescript
import type { EvaluationRun } from '@tripagent/tripagent-v1-sdk'

// TODO: Update the object below with actual values
const example = {
  "runId": null,
  "taskId": null,
  "datasetVersion": null,
  "status": null,
  "sampleSize": null,
  "metrics": null,
  "cases": null,
  "environment": null,
  "traceId": null,
  "startedAt": null,
  "completedAt": null,
  "createdAt": null,
  "updatedAt": null,
} satisfies EvaluationRun

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as EvaluationRun
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


