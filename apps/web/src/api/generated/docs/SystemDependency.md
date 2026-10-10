
# SystemDependency


## Properties

Name | Type
------------ | -------------
`key` | string
`label` | string
`status` | [AvailabilityStatus](AvailabilityStatus.md)

## Example

```typescript
import type { SystemDependency } from '@tripagent/tripagent-v1-sdk'

// TODO: Update the object below with actual values
const example = {
  "key": null,
  "label": null,
  "status": null,
} satisfies SystemDependency

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as SystemDependency
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


