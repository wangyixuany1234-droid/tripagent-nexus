
# ConfirmGuardedActionRequest


## Properties

Name | Type
------------ | -------------
`actionId` | string
`expectedVersion` | number
`acknowledgedRisk` | boolean
`acknowledgedAmount` | number
`currency` | string

## Example

```typescript
import type { ConfirmGuardedActionRequest } from '@tripagent/tripagent-v1-sdk'

// TODO: Update the object below with actual values
const example = {
  "actionId": null,
  "expectedVersion": null,
  "acknowledgedRisk": null,
  "acknowledgedAmount": null,
  "currency": null,
} satisfies ConfirmGuardedActionRequest

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ConfirmGuardedActionRequest
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


