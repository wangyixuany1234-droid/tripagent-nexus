
# ReplanInputSnapshot


## Properties

Name | Type
------------ | -------------
`lockedItemIds` | Set&lt;string&gt;
`removedItemIds` | Set&lt;string&gt;
`adjustedTotalBudget` | number
`adjustedPace` | string
`addedPreferences` | Set&lt;string&gt;
`naturalLanguageRequest` | string

## Example

```typescript
import type { ReplanInputSnapshot } from '@tripagent/tripagent-v1-sdk'

// TODO: Update the object below with actual values
const example = {
  "lockedItemIds": null,
  "removedItemIds": null,
  "adjustedTotalBudget": null,
  "adjustedPace": null,
  "addedPreferences": null,
  "naturalLanguageRequest": null,
} satisfies ReplanInputSnapshot

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ReplanInputSnapshot
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


