
# CreateGuardedActionRequest


## Properties

Name | Type
------------ | -------------
`type` | [GuardedActionType](GuardedActionType.md)
`resourceId` | string
`riskDescription` | string
`amount` | number
`currency` | string
`expiresAt` | Date

## Example

```typescript
import type { CreateGuardedActionRequest } from '@tripagent/tripagent-v1-sdk'

// TODO: Update the object below with actual values
const example = {
  "type": null,
  "resourceId": null,
  "riskDescription": null,
  "amount": null,
  "currency": null,
  "expiresAt": null,
} satisfies CreateGuardedActionRequest

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as CreateGuardedActionRequest
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


