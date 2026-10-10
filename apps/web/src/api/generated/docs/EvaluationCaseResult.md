
# EvaluationCaseResult


## Properties

Name | Type
------------ | -------------
`caseId` | string
`category` | string
`passed` | boolean
`latencyMs` | number
`tokenUsage` | number
`failureCode` | string
`observedCodes` | Set&lt;string&gt;

## Example

```typescript
import type { EvaluationCaseResult } from '@tripagent/tripagent-v1-sdk'

// TODO: Update the object below with actual values
const example = {
  "caseId": null,
  "category": null,
  "passed": null,
  "latencyMs": null,
  "tokenUsage": null,
  "failureCode": null,
  "observedCodes": null,
} satisfies EvaluationCaseResult

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as EvaluationCaseResult
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


