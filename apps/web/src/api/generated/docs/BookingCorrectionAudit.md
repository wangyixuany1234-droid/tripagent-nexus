
# BookingCorrectionAudit


## Properties

Name | Type
------------ | -------------
`auditId` | string
`field` | [BookingField](BookingField.md)
`originalValue` | string
`correctedValue` | string
`actorId` | string
`reason` | string
`correctedAt` | Date

## Example

```typescript
import type { BookingCorrectionAudit } from '@tripagent/tripagent-v1-sdk'

// TODO: Update the object below with actual values
const example = {
  "auditId": null,
  "field": null,
  "originalValue": null,
  "correctedValue": null,
  "actorId": null,
  "reason": null,
  "correctedAt": null,
} satisfies BookingCorrectionAudit

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as BookingCorrectionAudit
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


