
# DayPlan


## Properties

Name | Type
------------ | -------------
`dayNumber` | number
`date` | Date
`title` | string
`items` | [Array&lt;ItineraryItem&gt;](ItineraryItem.md)

## Example

```typescript
import type { DayPlan } from '@tripagent/tripagent-v1-sdk'

// TODO: Update the object below with actual values
const example = {
  "dayNumber": null,
  "date": null,
  "title": null,
  "items": null,
} satisfies DayPlan

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as DayPlan
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


