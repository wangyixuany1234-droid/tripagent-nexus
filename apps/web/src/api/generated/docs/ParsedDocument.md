
# ParsedDocument


## Properties

Name | Type
------------ | -------------
`documentId` | string
`tripId` | string
`fileName` | string
`mediaType` | string
`sizeBytes` | number
`sha256` | string
`pageCount` | number
`status` | [DocumentStatus](DocumentStatus.md)
`bookings` | [Array&lt;ParsedBooking&gt;](ParsedBooking.md)
`createdAt` | Date
`updatedAt` | Date

## Example

```typescript
import type { ParsedDocument } from '@tripagent/tripagent-v1-sdk'

// TODO: Update the object below with actual values
const example = {
  "documentId": null,
  "tripId": null,
  "fileName": null,
  "mediaType": null,
  "sizeBytes": null,
  "sha256": null,
  "pageCount": null,
  "status": null,
  "bookings": null,
  "createdAt": null,
  "updatedAt": null,
} satisfies ParsedDocument

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ParsedDocument
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


