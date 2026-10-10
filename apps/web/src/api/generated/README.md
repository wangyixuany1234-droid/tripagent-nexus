# @tripagent/tripagent-v1-sdk@1.0.0

A TypeScript SDK client for the localhost API.

## Usage

First, install the SDK from npm.

```bash
npm install @tripagent/tripagent-v1-sdk --save
```

Next, try it out.


```ts
import {
  Configuration,
  ActionsApi,
} from '@tripagent/tripagent-v1-sdk';
import type { ConfirmGuardedActionOperationRequest } from '@tripagent/tripagent-v1-sdk';

async function example() {
  console.log("🚀 Testing @tripagent/tripagent-v1-sdk SDK...");
  const config = new Configuration({ 
    // Configure HTTP bearer authorization: BearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new ActionsApi(config);

  const body = {
    // string | Caller-generated key scoped to operation and authenticated actor
    idempotencyKey: idempotencyKey_example,
    // string
    actionId: 38400000-8cf0-11bd-b23e-10b96e4ef00d,
    // ConfirmGuardedActionRequest
    confirmGuardedActionRequest: ...,
    // string | Legacy compatibility field; ignored for authorization when JWT authentication is enabled (optional)
    xActorId: xActorId_example,
  } satisfies ConfirmGuardedActionOperationRequest;

  try {
    const data = await api.confirmGuardedAction(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```


## Documentation

### API Endpoints

All URIs are relative to *http://localhost*

| Class | Method | HTTP request | Description
| ----- | ------ | ------------ | -------------
*ActionsApi* | [**confirmGuardedAction**](docs/ActionsApi.md#confirmguardedactionoperation) | **POST** /api/v1/actions/{actionId}/confirm | Confirm an unexpired candidate after acknowledging its risk and exact cost
*ActionsApi* | [**getGuardedAction**](docs/ActionsApi.md#getguardedaction) | **GET** /api/v1/actions/{actionId} | Get a candidate action and its immutable audit trail
*ActionsApi* | [**proposeGuardedAction**](docs/ActionsApi.md#proposeguardedaction) | **POST** /api/v1/actions | Create a non-executable candidate action awaiting explicit user confirmation
*ActionsApi* | [**rejectGuardedAction**](docs/ActionsApi.md#rejectguardedactionoperation) | **POST** /api/v1/actions/{actionId}/reject | Reject a candidate action and append an immutable audit record
*DeveloperApi* | [**getDeveloperTrace**](docs/DeveloperApi.md#getdevelopertrace) | **GET** /api/v1/developer/traces/{traceId} | Get a sanitized trace in development or for administrators
*DocumentsApi* | [**correctParsedBooking**](docs/DocumentsApi.md#correctparsedbooking) | **PATCH** /api/v1/documents/{documentId}/bookings/{bookingId} | Correct extracted booking fields and append an immutable audit record
*DocumentsApi* | [**getParsedDocument**](docs/DocumentsApi.md#getparseddocument) | **GET** /api/v1/documents/{documentId} | Get document parsing state and parsed bookings
*DocumentsApi* | [**parseDocument**](docs/DocumentsApi.md#parsedocument) | **POST** /api/v1/documents/parse | Accept a document and queue asynchronous parsing
*DocumentsApi* | [**retryDocumentParsing**](docs/DocumentsApi.md#retrydocumentparsing) | **POST** /api/v1/documents/{documentId}/retry | Retry parsing for a failed document without uploading its content again
*EvaluationsApi* | [**getEvaluationRun**](docs/EvaluationsApi.md#getevaluationrun) | **GET** /api/v1/evaluations/runs/{runId} | Get aggregate metrics, sample outcomes, failures, and execution environment
*EvaluationsApi* | [**startEvaluationRun**](docs/EvaluationsApi.md#startevaluationrun) | **POST** /api/v1/evaluations/runs | Queue a versioned Harness Engineering dataset replay
*FeedbackApi* | [**createFeedback**](docs/FeedbackApi.md#createfeedbackoperation) | **POST** /api/v1/feedback | Record user feedback for a trip or plan
*FeedbackApi* | [**listFeedback**](docs/FeedbackApi.md#listfeedback) | **GET** /api/v1/feedback | List feedback belonging to one trip
*MapsApi* | [**getMapTile**](docs/MapsApi.md#getmaptile) | **GET** /api/v1/maps/tiles/{zoom}/{x}/{y}.png | Get a cached world map tile through the backend allowlist
*OperationsApi* | [**listDeadLetters**](docs/OperationsApi.md#listdeadletters) | **GET** /api/v1/operations/dead-letters | List retained dead-letter events
*OperationsApi* | [**replayDeadLetter**](docs/OperationsApi.md#replaydeadletteroperation) | **POST** /api/v1/operations/dead-letters/{deadLetterId}/replay | Republish one retained dead-letter event
*PlansApi* | [**compareTravelPlanVersions**](docs/PlansApi.md#comparetravelplanversions) | **GET** /api/v1/plans/{planId}/compare/{otherPlanId} | Compare two immutable versions from the same plan family
*PlansApi* | [**confirmTravelPlan**](docs/PlansApi.md#confirmtravelplan) | **POST** /api/v1/plans/{planId}/confirm | Confirm a generated travel plan without executing provider actions
*PlansApi* | [**createTravelPlan**](docs/PlansApi.md#createtravelplanoperation) | **POST** /api/v1/plans | Reserve a plan resource and queue asynchronous generation
*PlansApi* | [**getTravelPlan**](docs/PlansApi.md#gettravelplan) | **GET** /api/v1/plans/{planId} | Get one travel plan
*PlansApi* | [**listTravelPlans**](docs/PlansApi.md#listtravelplans) | **GET** /api/v1/trips/{tripId}/plans | List plans belonging to a trip
*PlansApi* | [**replanTravelPlan**](docs/PlansApi.md#replantravelplan) | **POST** /api/v1/plans/{planId}/replan | Create a successor plan version and queue regeneration
*PreferencesApi* | [**createUserPreference**](docs/PreferencesApi.md#createuserpreference) | **POST** /api/v1/users/{userId}/preferences | Create a user-provided preference
*PreferencesApi* | [**deleteUserPreference**](docs/PreferencesApi.md#deleteuserpreference) | **DELETE** /api/v1/users/{userId}/preferences/{preferenceId} | Delete a preference and request backend memory cleanup
*PreferencesApi* | [**listUserPreferences**](docs/PreferencesApi.md#listuserpreferences) | **GET** /api/v1/users/{userId}/preferences | List active and rejected preferences controlled by the user
*PreferencesApi* | [**rejectInferredPreference**](docs/PreferencesApi.md#rejectinferredpreference) | **POST** /api/v1/users/{userId}/preferences/{preferenceId}/reject | Reject an inferred preference and request backend memory cleanup
*PreferencesApi* | [**updateUserPreference**](docs/PreferencesApi.md#updateuserpreference) | **PUT** /api/v1/users/{userId}/preferences/{preferenceId} | Update a preference value
*SystemApi* | [**getSystemHealth**](docs/SystemApi.md#getsystemhealth) | **GET** /api/v1/health | Get public API availability and provider mode
*SystemApi* | [**getSystemStatus**](docs/SystemApi.md#getsystemstatus) | **GET** /api/v1/system/status | Get sanitized dependency and capability availability
*TasksApi* | [**cancelTask**](docs/TasksApi.md#canceltask) | **POST** /api/v1/tasks/{taskId}/cancel | Cancel a non-terminal task
*TasksApi* | [**getTask**](docs/TasksApi.md#gettask) | **GET** /api/v1/tasks/{taskId} | Get asynchronous task state
*TasksApi* | [**listTaskAgentExecutions**](docs/TasksApi.md#listtaskagentexecutions) | **GET** /api/v1/tasks/{taskId}/agents | List backend-observed Agent execution states for a planning task
*TasksApi* | [**streamTaskEvents**](docs/TasksApi.md#streamtaskevents) | **GET** /api/v1/tasks/{taskId}/events | Stream persisted and live task progress
*TripsApi* | [**createTrip**](docs/TripsApi.md#createtripoperation) | **POST** /api/v1/trips | Create a trip project
*TripsApi* | [**getTrip**](docs/TripsApi.md#gettrip) | **GET** /api/v1/trips/{tripId} | Get a trip project
*TripsApi* | [**saveTravelRequirement**](docs/TripsApi.md#savetravelrequirementoperation) | **PUT** /api/v1/trips/{tripId}/requirements | Create or replace the normalized travel requirement
*WorkbenchApi* | [**listWorkbenchTasks**](docs/WorkbenchApi.md#listworkbenchtasks) | **GET** /api/v1/workbench/tasks | List user-scoped travel tasks with filters


### Models

- [ActionAuditRecord](docs/ActionAuditRecord.md)
- [AgentExecution](docs/AgentExecution.md)
- [AgentExecutionList](docs/AgentExecutionList.md)
- [AgentExecutionStatus](docs/AgentExecutionStatus.md)
- [AgentExecutionWarning](docs/AgentExecutionWarning.md)
- [AgentResult](docs/AgentResult.md)
- [ApiError](docs/ApiError.md)
- [AvailabilityStatus](docs/AvailabilityStatus.md)
- [BookingCorrectionAudit](docs/BookingCorrectionAudit.md)
- [BookingCorrectionRequest](docs/BookingCorrectionRequest.md)
- [BookingField](docs/BookingField.md)
- [BookingFieldCorrection](docs/BookingFieldCorrection.md)
- [BookingReviewStatus](docs/BookingReviewStatus.md)
- [BookingType](docs/BookingType.md)
- [BookingValidationIssue](docs/BookingValidationIssue.md)
- [BudgetItem](docs/BudgetItem.md)
- [BudgetSummary](docs/BudgetSummary.md)
- [ConfirmGuardedActionRequest](docs/ConfirmGuardedActionRequest.md)
- [ConfirmPlanRequest](docs/ConfirmPlanRequest.md)
- [Conflict](docs/Conflict.md)
- [CreateEvaluationRunRequest](docs/CreateEvaluationRunRequest.md)
- [CreateFeedbackRequest](docs/CreateFeedbackRequest.md)
- [CreateGuardedActionRequest](docs/CreateGuardedActionRequest.md)
- [CreateTravelPlanRequest](docs/CreateTravelPlanRequest.md)
- [CreateTripRequest](docs/CreateTripRequest.md)
- [DailyBudget](docs/DailyBudget.md)
- [DayPlan](docs/DayPlan.md)
- [DeadLetterList](docs/DeadLetterList.md)
- [DeadLetterRecord](docs/DeadLetterRecord.md)
- [DeadLetterStatus](docs/DeadLetterStatus.md)
- [DeveloperTrace](docs/DeveloperTrace.md)
- [DocumentStatus](docs/DocumentStatus.md)
- [ErrorDetail](docs/ErrorDetail.md)
- [EvaluationCaseResult](docs/EvaluationCaseResult.md)
- [EvaluationEnvironment](docs/EvaluationEnvironment.md)
- [EvaluationMetrics](docs/EvaluationMetrics.md)
- [EvaluationRun](docs/EvaluationRun.md)
- [EvaluationRunStatus](docs/EvaluationRunStatus.md)
- [Evidence](docs/Evidence.md)
- [EvidenceRegion](docs/EvidenceRegion.md)
- [ExtractedFieldEvidence](docs/ExtractedFieldEvidence.md)
- [ExtractedFieldStatus](docs/ExtractedFieldStatus.md)
- [Feedback](docs/Feedback.md)
- [FeedbackFields](docs/FeedbackFields.md)
- [FeedbackList](docs/FeedbackList.md)
- [FeedbackSentiment](docs/FeedbackSentiment.md)
- [FeedbackTargetType](docs/FeedbackTargetType.md)
- [GuardedAction](docs/GuardedAction.md)
- [GuardedActionStatus](docs/GuardedActionStatus.md)
- [GuardedActionType](docs/GuardedActionType.md)
- [ItineraryItem](docs/ItineraryItem.md)
- [Pace](docs/Pace.md)
- [ParsedBooking](docs/ParsedBooking.md)
- [ParsedDocument](docs/ParsedDocument.md)
- [Place](docs/Place.md)
- [PlanComparison](docs/PlanComparison.md)
- [PlanStatus](docs/PlanStatus.md)
- [PlanStrategy](docs/PlanStrategy.md)
- [PreferenceSource](docs/PreferenceSource.md)
- [PreferenceStatus](docs/PreferenceStatus.md)
- [RejectGuardedActionRequest](docs/RejectGuardedActionRequest.md)
- [ReplanInputSnapshot](docs/ReplanInputSnapshot.md)
- [ReplanRequest](docs/ReplanRequest.md)
- [ReplayDeadLetterRequest](docs/ReplayDeadLetterRequest.md)
- [SaveTravelRequirementRequest](docs/SaveTravelRequirementRequest.md)
- [SaveUserPreferenceRequest](docs/SaveUserPreferenceRequest.md)
- [SystemCapability](docs/SystemCapability.md)
- [SystemDependency](docs/SystemDependency.md)
- [SystemHealth](docs/SystemHealth.md)
- [SystemStatus](docs/SystemStatus.md)
- [TaskAccepted](docs/TaskAccepted.md)
- [TaskEvent](docs/TaskEvent.md)
- [TaskRecord](docs/TaskRecord.md)
- [TaskStatus](docs/TaskStatus.md)
- [TaskType](docs/TaskType.md)
- [TraceSpan](docs/TraceSpan.md)
- [TraceSpanCategory](docs/TraceSpanCategory.md)
- [TransportMode](docs/TransportMode.md)
- [TravelPlan](docs/TravelPlan.md)
- [TravelPlanList](docs/TravelPlanList.md)
- [TravelRequirement](docs/TravelRequirement.md)
- [TravelRequirementFields](docs/TravelRequirementFields.md)
- [Traveler](docs/Traveler.md)
- [Trip](docs/Trip.md)
- [TripStatus](docs/TripStatus.md)
- [UserPreference](docs/UserPreference.md)
- [UserPreferenceList](docs/UserPreferenceList.md)
- [UserProfile](docs/UserProfile.md)
- [WorkbenchTask](docs/WorkbenchTask.md)
- [WorkbenchTaskList](docs/WorkbenchTaskList.md)

### Authorization


Authentication schemes defined for the API:
<a id="BearerAuth"></a>
#### BearerAuth


- **Type**: HTTP Bearer Token authentication (JWT)
<a id="OperationsToken"></a>
#### OperationsToken


- **Type**: API key
- **API key parameter name**: `X-Operations-Token`
- **Location**: HTTP header

## About

This TypeScript SDK client supports the [Fetch API](https://fetch.spec.whatwg.org/)
and is automatically generated by the
[OpenAPI Generator](https://openapi-generator.tech) project:

- API version: `1.0.0`
- Package version: `1.0.0`
- Generator version: `7.25.0`
- Build package: `org.openapitools.codegen.languages.TypeScriptFetchClientCodegen`

The generated npm module supports the following:

- Environments
  * Node.js
  * Webpack
  * Browserify
- Language levels
  * ES5 - you must have a Promises/A+ library installed
  * ES6
- Module systems
  * CommonJS
  * ES6 module system


## Development

### Building

To build the TypeScript source code, you need to have Node.js and npm installed.
After cloning the repository, navigate to the project directory and run:

```bash
npm install
npm run build
```

### Publishing

Once you've built the package, you can publish it to npm:

```bash
npm publish
```

## License

[Proprietary]()
