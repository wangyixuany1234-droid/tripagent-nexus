
# EvaluationMetrics


## Properties

Name | Type
------------ | -------------
`itineraryExecutabilityRate` | number
`constraintConflictRate` | number
`evidenceCoverageRate` | number
`toolCallSuccessRate` | number
`structuredOutputValidityRate` | number
`replanSuccessRate` | number
`endToEndLatencyMs` | number
`tokenUsage` | number

## Example

```typescript
import type { EvaluationMetrics } from '@tripagent/tripagent-v1-sdk'

// TODO: Update the object below with actual values
const example = {
  "itineraryExecutabilityRate": null,
  "constraintConflictRate": null,
  "evidenceCoverageRate": null,
  "toolCallSuccessRate": null,
  "structuredOutputValidityRate": null,
  "replanSuccessRate": null,
  "endToEndLatencyMs": null,
  "tokenUsage": null,
} satisfies EvaluationMetrics

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as EvaluationMetrics
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


