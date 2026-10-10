
# UserProfile


## Properties

Name | Type
------------ | -------------
`userId` | string
`preferredPace` | [Pace](Pace.md)
`interests` | Set&lt;string&gt;
`dietaryRestrictions` | Set&lt;string&gt;
`accommodationPreferences` | Set&lt;string&gt;
`transportPreferences` | [Set&lt;TransportMode&gt;](TransportMode.md)
`budgetHabits` | Set&lt;string&gt;

## Example

```typescript
import type { UserProfile } from '@tripagent/tripagent-v1-sdk'

// TODO: Update the object below with actual values
const example = {
  "userId": null,
  "preferredPace": null,
  "interests": null,
  "dietaryRestrictions": null,
  "accommodationPreferences": null,
  "transportPreferences": null,
  "budgetHabits": null,
} satisfies UserProfile

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as UserProfile
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


