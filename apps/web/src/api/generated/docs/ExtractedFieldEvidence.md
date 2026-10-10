
# ExtractedFieldEvidence

Field-level OCR evidence. Non-missing fields should include textEvidence, region, or both; this cross-field rule is enforced by the backend validator. 

## Properties

Name | Type
------------ | -------------
`field` | [BookingField](BookingField.md)
`confidence` | number
`status` | [ExtractedFieldStatus](ExtractedFieldStatus.md)
`textEvidence` | string
`region` | [EvidenceRegion](EvidenceRegion.md)

## Example

```typescript
import type { ExtractedFieldEvidence } from '@tripagent/tripagent-v1-sdk'

// TODO: Update the object below with actual values
const example = {
  "field": null,
  "confidence": null,
  "status": null,
  "textEvidence": null,
  "region": null,
} satisfies ExtractedFieldEvidence

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ExtractedFieldEvidence
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


