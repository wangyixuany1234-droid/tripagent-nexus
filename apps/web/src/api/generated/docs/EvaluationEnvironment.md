
# EvaluationEnvironment


## Properties

Name | Type
------------ | -------------
`applicationVersion` | string
`javaVersion` | string
`os` | string
`modelProvider` | string
`modelName` | string
`mockMode` | boolean

## Example

```typescript
import type { EvaluationEnvironment } from '@tripagent/tripagent-v1-sdk'

// TODO: Update the object below with actual values
const example = {
  "applicationVersion": null,
  "javaVersion": null,
  "os": null,
  "modelProvider": null,
  "modelName": null,
  "mockMode": null,
} satisfies EvaluationEnvironment

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as EvaluationEnvironment
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


