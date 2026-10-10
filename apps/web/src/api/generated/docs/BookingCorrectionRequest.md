
# BookingCorrectionRequest


## Properties

Name | Type
------------ | -------------
`corrections` | [Array&lt;BookingFieldCorrection&gt;](BookingFieldCorrection.md)
`reason` | string

## Example

```typescript
import type { BookingCorrectionRequest } from '@tripagent/tripagent-v1-sdk'

// TODO: Update the object below with actual values
const example = {
  "corrections": null,
  "reason": null,
} satisfies BookingCorrectionRequest

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as BookingCorrectionRequest
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


