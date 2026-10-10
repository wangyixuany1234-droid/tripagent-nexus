
# BudgetItem


## Properties

Name | Type
------------ | -------------
`category` | string
`description` | string
`amount` | number
`currency` | string

## Example

```typescript
import type { BudgetItem } from '@tripagent/tripagent-v1-sdk'

// TODO: Update the object below with actual values
const example = {
  "category": null,
  "description": null,
  "amount": null,
  "currency": null,
} satisfies BudgetItem

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as BudgetItem
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


