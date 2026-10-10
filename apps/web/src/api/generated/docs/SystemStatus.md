
# SystemStatus


## Properties

Name | Type
------------ | -------------
`status` | [AvailabilityStatus](AvailabilityStatus.md)
`providerMode` | string
`modelProvider` | string
`developerToolsEnabled` | boolean
`dependencies` | [Array&lt;SystemDependency&gt;](SystemDependency.md)
`capabilities` | [Array&lt;SystemCapability&gt;](SystemCapability.md)
`checkedAt` | Date

## Example

```typescript
import type { SystemStatus } from '@tripagent/tripagent-v1-sdk'

// TODO: Update the object below with actual values
const example = {
  "status": null,
  "providerMode": null,
  "modelProvider": null,
  "developerToolsEnabled": null,
  "dependencies": null,
  "capabilities": null,
  "checkedAt": null,
} satisfies SystemStatus

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as SystemStatus
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


