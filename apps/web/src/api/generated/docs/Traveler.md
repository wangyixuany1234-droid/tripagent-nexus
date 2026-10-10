
# Traveler


## Properties

Name | Type
------------ | -------------
`travelerId` | string
`displayName` | string
`type` | string
`accessibilityNeeds` | Array&lt;string&gt;

## Example

```typescript
import type { Traveler } from '@tripagent/tripagent-v1-sdk'

// TODO: Update the object below with actual values
const example = {
  "travelerId": null,
  "displayName": null,
  "type": null,
  "accessibilityNeeds": null,
} satisfies Traveler

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as Traveler
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


