
# ItineraryItem


## Properties

Name | Type
------------ | -------------
`itemId` | string
`type` | string
`title` | string
`description` | string
`startTime` | Date
`endTime` | Date
`location` | [Place](Place.md)
`amount` | number
`currency` | string
`evidenceIds` | Set&lt;string&gt;

## Example

```typescript
import type { ItineraryItem } from '@tripagent/tripagent-v1-sdk'

// TODO: Update the object below with actual values
const example = {
  "itemId": null,
  "type": null,
  "title": null,
  "description": null,
  "startTime": null,
  "endTime": null,
  "location": null,
  "amount": null,
  "currency": null,
  "evidenceIds": null,
} satisfies ItineraryItem

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ItineraryItem
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


