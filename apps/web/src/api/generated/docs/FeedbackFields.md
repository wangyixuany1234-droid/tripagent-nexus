
# FeedbackFields


## Properties

Name | Type
------------ | -------------
`tripId` | string
`planId` | string
`targetType` | [FeedbackTargetType](FeedbackTargetType.md)
`targetId` | string
`sentiment` | [FeedbackSentiment](FeedbackSentiment.md)
`rating` | number
`comment` | string
`tags` | Set&lt;string&gt;

## Example

```typescript
import type { FeedbackFields } from '@tripagent/tripagent-v1-sdk'

// TODO: Update the object below with actual values
const example = {
  "tripId": null,
  "planId": null,
  "targetType": null,
  "targetId": null,
  "sentiment": null,
  "rating": null,
  "comment": null,
  "tags": null,
} satisfies FeedbackFields

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as FeedbackFields
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


