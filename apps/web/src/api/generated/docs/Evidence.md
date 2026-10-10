
# Evidence


## Properties

Name | Type
------------ | -------------
`evidenceId` | string
`sourceType` | string
`sourceId` | string
`title` | string
`excerpt` | string
`url` | string
`retrievedAt` | Date

## Example

```typescript
import type { Evidence } from '@tripagent/tripagent-v1-sdk'

// TODO: Update the object below with actual values
const example = {
  "evidenceId": null,
  "sourceType": null,
  "sourceId": null,
  "title": null,
  "excerpt": null,
  "url": null,
  "retrievedAt": null,
} satisfies Evidence

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as Evidence
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


