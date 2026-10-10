
# TravelPlan


## Properties

Name | Type
------------ | -------------
`planId` | string
`planFamilyId` | string
`tripId` | string
`previousPlanId` | string
`version` | number
`alternativeKey` | string
`strategy` | [PlanStrategy](PlanStrategy.md)
`title` | string
`summary` | string
`days` | [Array&lt;DayPlan&gt;](DayPlan.md)
`budgetSummary` | [BudgetSummary](BudgetSummary.md)
`conflicts` | [Array&lt;Conflict&gt;](Conflict.md)
`evidence` | [Array&lt;Evidence&gt;](Evidence.md)
`triggerReason` | string
`replanInput` | [ReplanInputSnapshot](ReplanInputSnapshot.md)
`changeSummary` | string
`autoReplanned` | boolean
`status` | [PlanStatus](PlanStatus.md)
`createdAt` | Date
`updatedAt` | Date

## Example

```typescript
import type { TravelPlan } from '@tripagent/tripagent-v1-sdk'

// TODO: Update the object below with actual values
const example = {
  "planId": null,
  "planFamilyId": null,
  "tripId": null,
  "previousPlanId": null,
  "version": null,
  "alternativeKey": null,
  "strategy": null,
  "title": null,
  "summary": null,
  "days": null,
  "budgetSummary": null,
  "conflicts": null,
  "evidence": null,
  "triggerReason": null,
  "replanInput": null,
  "changeSummary": null,
  "autoReplanned": null,
  "status": null,
  "createdAt": null,
  "updatedAt": null,
} satisfies TravelPlan

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as TravelPlan
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


