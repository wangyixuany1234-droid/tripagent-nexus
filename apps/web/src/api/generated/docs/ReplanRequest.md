
# ReplanRequest


## Properties

Name | Type
------------ | -------------
`reason` | string
`freeTextConstraints` | string
`lockedItemIds` | Set&lt;string&gt;
`removedItemIds` | Set&lt;string&gt;
`adjustedTotalBudget` | number
`adjustedPace` | string
`addedPreferences` | Set&lt;string&gt;
`naturalLanguageRequest` | string

## Example

```typescript
import type { ReplanRequest } from '@tripagent/tripagent-v1-sdk'

// TODO: Update the object below with actual values
const example = {
  "reason": null,
  "freeTextConstraints": null,
  "lockedItemIds": null,
  "removedItemIds": null,
  "adjustedTotalBudget": null,
  "adjustedPace": null,
  "addedPreferences": null,
  "naturalLanguageRequest": null,
} satisfies ReplanRequest

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ReplanRequest
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


