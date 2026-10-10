
# Trip


## Properties

Name | Type
------------ | -------------
`tripId` | string
`userId` | string
`title` | string
`timeZone` | string
`status` | [TripStatus](TripStatus.md)
`requirement` | [TravelRequirement](TravelRequirement.md)
`createdAt` | Date
`updatedAt` | Date

## Example

```typescript
import type { Trip } from '@tripagent/tripagent-v1-sdk'

// TODO: Update the object below with actual values
const example = {
  "tripId": null,
  "userId": null,
  "title": null,
  "timeZone": null,
  "status": null,
  "requirement": null,
  "createdAt": null,
  "updatedAt": null,
} satisfies Trip

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as Trip
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


