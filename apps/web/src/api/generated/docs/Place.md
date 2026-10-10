
# Place


## Properties

Name | Type
------------ | -------------
`placeId` | string
`name` | string
`address` | string
`latitude` | number
`longitude` | number

## Example

```typescript
import type { Place } from '@tripagent/tripagent-v1-sdk'

// TODO: Update the object below with actual values
const example = {
  "placeId": null,
  "name": null,
  "address": null,
  "latitude": null,
  "longitude": null,
} satisfies Place

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as Place
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


