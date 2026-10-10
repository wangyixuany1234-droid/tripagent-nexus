
# SaveTravelRequirementRequest


## Properties

Name | Type
------------ | -------------
`origin` | [Place](Place.md)
`destinations` | [Array&lt;Place&gt;](Place.md)
`startDate` | Date
`endDate` | Date
`travelers` | [Array&lt;Traveler&gt;](Traveler.md)
`totalBudget` | number
`currency` | string
`interests` | Set&lt;string&gt;
`pace` | [Pace](Pace.md)
`mustVisit` | Set&lt;string&gt;
`avoid` | Set&lt;string&gt;
`freeTextConstraints` | string

## Example

```typescript
import type { SaveTravelRequirementRequest } from '@tripagent/tripagent-v1-sdk'

// TODO: Update the object below with actual values
const example = {
  "origin": null,
  "destinations": null,
  "startDate": null,
  "endDate": null,
  "travelers": null,
  "totalBudget": null,
  "currency": null,
  "interests": null,
  "pace": null,
  "mustVisit": null,
  "avoid": null,
  "freeTextConstraints": null,
} satisfies SaveTravelRequirementRequest

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as SaveTravelRequirementRequest
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


