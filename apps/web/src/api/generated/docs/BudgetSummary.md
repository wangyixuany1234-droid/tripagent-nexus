
# BudgetSummary


## Properties

Name | Type
------------ | -------------
`totalBudget` | number
`estimatedTotal` | number
`remainingBudget` | number
`currency` | string
`items` | [Array&lt;BudgetItem&gt;](BudgetItem.md)
`dailyBudgets` | [Array&lt;DailyBudget&gt;](DailyBudget.md)

## Example

```typescript
import type { BudgetSummary } from '@tripagent/tripagent-v1-sdk'

// TODO: Update the object below with actual values
const example = {
  "totalBudget": null,
  "estimatedTotal": null,
  "remainingBudget": null,
  "currency": null,
  "items": null,
  "dailyBudgets": null,
} satisfies BudgetSummary

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as BudgetSummary
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


