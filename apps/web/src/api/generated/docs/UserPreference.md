
# UserPreference


## Properties

Name | Type
------------ | -------------
`preferenceId` | string
`userId` | string
`category` | string
`value` | string
`source` | [PreferenceSource](PreferenceSource.md)
`status` | [PreferenceStatus](PreferenceStatus.md)
`createdAt` | Date
`updatedAt` | Date

## Example

```typescript
import type { UserPreference } from '@tripagent/tripagent-v1-sdk'

// TODO: Update the object below with actual values
const example = {
  "preferenceId": null,
  "userId": null,
  "category": null,
  "value": null,
  "source": null,
  "status": null,
  "createdAt": null,
  "updatedAt": null,
} satisfies UserPreference

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as UserPreference
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


