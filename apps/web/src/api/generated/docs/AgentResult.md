
# AgentResult


## Properties

Name | Type
------------ | -------------
`agentResultId` | string
`taskId` | string
`agentType` | string
`status` | [TaskStatus](TaskStatus.md)
`result` | object
`evidenceIds` | Array&lt;string&gt;
`errors` | [Array&lt;ErrorDetail&gt;](ErrorDetail.md)
`createdAt` | Date

## Example

```typescript
import type { AgentResult } from '@tripagent/tripagent-v1-sdk'

// TODO: Update the object below with actual values
const example = {
  "agentResultId": null,
  "taskId": null,
  "agentType": null,
  "status": null,
  "result": null,
  "evidenceIds": null,
  "errors": null,
  "createdAt": null,
} satisfies AgentResult

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as AgentResult
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


