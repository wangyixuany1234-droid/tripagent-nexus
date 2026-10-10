
# GuardedAction


## Properties

Name | Type
------------ | -------------
`actionId` | string
`type` | [GuardedActionType](GuardedActionType.md)
`resourceId` | string
`status` | [GuardedActionStatus](GuardedActionStatus.md)
`riskDescription` | string
`amount` | number
`currency` | string
`expiresAt` | Date
`proposedBy` | string
`confirmedBy` | string
`version` | number
`auditTrail` | [Array&lt;ActionAuditRecord&gt;](ActionAuditRecord.md)
`createdAt` | Date
`updatedAt` | Date

## Example

```typescript
import type { GuardedAction } from '@tripagent/tripagent-v1-sdk'

// TODO: Update the object below with actual values
const example = {
  "actionId": null,
  "type": null,
  "resourceId": null,
  "status": null,
  "riskDescription": null,
  "amount": null,
  "currency": null,
  "expiresAt": null,
  "proposedBy": null,
  "confirmedBy": null,
  "version": null,
  "auditTrail": null,
  "createdAt": null,
  "updatedAt": null,
} satisfies GuardedAction

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as GuardedAction
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


