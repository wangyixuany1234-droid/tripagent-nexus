
# PlanComparison


## Properties

Name | Type
------------ | -------------
`planFamilyId` | string
`fromPlanId` | string
`toPlanId` | string
`fromVersion` | number
`toVersion` | number
`budgetDelta` | number
`addedItemIds` | Set&lt;string&gt;
`removedItemIds` | Set&lt;string&gt;
`changedItemIds` | Set&lt;string&gt;
`resolvedConflictCodes` | Set&lt;string&gt;
`introducedConflictCodes` | Set&lt;string&gt;
`summary` | string

## Example

```typescript
import type { PlanComparison } from '@tripagent/tripagent-v1-sdk'

// TODO: Update the object below with actual values
const example = {
  "planFamilyId": null,
  "fromPlanId": null,
  "toPlanId": null,
  "fromVersion": null,
  "toVersion": null,
  "budgetDelta": null,
  "addedItemIds": null,
  "removedItemIds": null,
  "changedItemIds": null,
  "resolvedConflictCodes": null,
  "introducedConflictCodes": null,
  "summary": null,
} satisfies PlanComparison

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as PlanComparison
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


