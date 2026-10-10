
# Conflict


## Properties

Name | Type
------------ | -------------
`conflictId` | string
`code` | string
`type` | string
`severity` | string
`message` | string
`fieldPath` | string
`relatedItemIds` | Set&lt;string&gt;
`autoFixable` | boolean
`status` | string

## Example

```typescript
import type { Conflict } from '@tripagent/tripagent-v1-sdk'

// TODO: Update the object below with actual values
const example = {
  "conflictId": null,
  "code": null,
  "type": null,
  "severity": null,
  "message": null,
  "fieldPath": null,
  "relatedItemIds": null,
  "autoFixable": null,
  "status": null,
} satisfies Conflict

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as Conflict
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


