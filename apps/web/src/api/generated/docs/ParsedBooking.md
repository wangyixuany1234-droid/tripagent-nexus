
# ParsedBooking


## Properties

Name | Type
------------ | -------------
`bookingId` | string
`type` | [BookingType](BookingType.md)
`sourceDocumentId` | string
`provider` | string
`origin` | string
`destination` | string
`hotelName` | string
`hotelAddress` | string
`transportNumber` | string
`confirmationNumber` | string
`startTime` | Date
`endTime` | Date
`travelerCount` | number
`amount` | number
`currency` | string
`confidence` | number
`missingFields` | Set&lt;string&gt;
`reviewStatus` | [BookingReviewStatus](BookingReviewStatus.md)
`fieldEvidence` | [Array&lt;ExtractedFieldEvidence&gt;](ExtractedFieldEvidence.md)
`validationIssues` | [Array&lt;BookingValidationIssue&gt;](BookingValidationIssue.md)
`corrections` | [Array&lt;BookingCorrectionAudit&gt;](BookingCorrectionAudit.md)

## Example

```typescript
import type { ParsedBooking } from '@tripagent/tripagent-v1-sdk'

// TODO: Update the object below with actual values
const example = {
  "bookingId": null,
  "type": null,
  "sourceDocumentId": null,
  "provider": null,
  "origin": null,
  "destination": null,
  "hotelName": null,
  "hotelAddress": null,
  "transportNumber": null,
  "confirmationNumber": null,
  "startTime": null,
  "endTime": null,
  "travelerCount": null,
  "amount": null,
  "currency": null,
  "confidence": null,
  "missingFields": null,
  "reviewStatus": null,
  "fieldEvidence": null,
  "validationIssues": null,
  "corrections": null,
} satisfies ParsedBooking

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ParsedBooking
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


