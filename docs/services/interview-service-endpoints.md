# interview-service — REST endpoint reference

Generated from current Java controller annotations. This is a source-level reference, not proof of deployed reachability or live acceptance. See [service design](interview-service.md) for business rules and end-to-end flows, and [shared conventions](END_TO_END_DESIGN.md#api-and-security-conventions).

Mapped operations: **20**. Framework-generated Actuator endpoints and gateway-proxied routes are not duplicated here. Signatures preserve request headers, query defaults, body types, return types and optional bindings. Java types are not JSON field names: request/response record fields are listed below. Controller checks may delegate additional authorization to application services; an empty extracted-check list does not mean public access.

## InterviewController

Source: [InterviewController](../../interview-service/src/main/java/com/openhire/interview/adapter/in/web/InterviewController.java).

### POST `/api/v1/applications/{applicationId}/interview-loop`

- Handler: `createLoop` ([source](../../interview-service/src/main/java/com/openhire/interview/adapter/in/web/InterviewController.java#L58)).
- Success status: \@ResponseStatus(HttpStatus.CREATED).
- Controller access/tenant calls: `viewers.coordinator(auth)`; `viewers.tenant(auth)`.
- Delegated operations: `interviews.createLoop`.

Request bindings and declared response:

```java
LoopView createLoop(Authentication auth, @PathVariable String applicationId)
```

### GET `/api/v1/applications/{applicationId}/interview-loop`

- Handler: `loop` ([source](../../interview-service/src/main/java/com/openhire/interview/adapter/in/web/InterviewController.java#L65)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.coordinator(auth)`; `viewers.tenant(auth)`.
- Delegated operations: `interviews.loop`.

Request bindings and declared response:

```java
LoopView loop(Authentication auth, @PathVariable String applicationId)
```

### GET `/api/v1/interviews`

- Handler: `week` ([source](../../interview-service/src/main/java/com/openhire/interview/adapter/in/web/InterviewController.java#L71)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.participant(auth)`; `viewers.tenant(auth)`.
- Delegated operations: `interviews.week`.

Request bindings and declared response:

```java
Page<InterviewEventView> week(Authentication auth, @RequestParam(required = false) String week)
```

### GET `/api/v1/interviews/unscheduled`

- Handler: `unscheduled` ([source](../../interview-service/src/main/java/com/openhire/interview/adapter/in/web/InterviewController.java#L83)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.coordinator(auth)`; `viewers.tenant(auth)`.
- Delegated operations: `interviews.unscheduled`.

Request bindings and declared response:

```java
Page<UnscheduledRoundView> unscheduled(Authentication auth)
```

### GET `/api/v1/me/interviews`

- Handler: `mine` ([source](../../interview-service/src/main/java/com/openhire/interview/adapter/in/web/InterviewController.java#L89)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.participant(auth)`; `viewers.tenant(auth)`.
- Delegated operations: `interviews.myInterviews`.

Request bindings and declared response:

```java
Page<MyInterviewView> mine(Authentication auth)
```

### POST `/api/v1/rounds/{roundId}/schedule`

- Handler: `schedule` ([source](../../interview-service/src/main/java/com/openhire/interview/adapter/in/web/InterviewController.java#L95)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.coordinator(auth)`; `viewers.tenant(auth)`.
- Delegated operations: `interviews.schedule`.

Request bindings and declared response:

```java
RoundView schedule(Authentication auth, @PathVariable String roundId, @RequestBody ScheduleInput input)
```

### PUT `/api/v1/rounds/{roundId}/panel`

- Handler: `assignPanel` ([source](../../interview-service/src/main/java/com/openhire/interview/adapter/in/web/InterviewController.java#L115)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.coordinator(auth)`; `viewers.tenant(auth)`.
- Delegated operations: `interviews.assignPanel`.

Request bindings and declared response:

```java
RoundView assignPanel(Authentication auth, @PathVariable String roundId, @RequestBody PanelAssignment input)
```

### POST `/api/v1/rounds/{roundId}/reschedule`

- Handler: `reschedule` ([source](../../interview-service/src/main/java/com/openhire/interview/adapter/in/web/InterviewController.java#L126)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.coordinator(auth)`; `viewers.tenant(auth)`.
- Delegated operations: `interviews.reschedule`.

Request bindings and declared response:

```java
Map<String, String> reschedule( Authentication auth, @PathVariable String roundId, @RequestBody RescheduleInput input)
```

### POST `/api/v1/rounds/{roundId}/cancel`

- Handler: `cancel` ([source](../../interview-service/src/main/java/com/openhire/interview/adapter/in/web/InterviewController.java#L134)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.coordinator(auth)`; `viewers.tenant(auth)`.
- Delegated operations: `interviews.cancel`.

Request bindings and declared response:

```java
Map<String, String> cancel( Authentication auth, @PathVariable String roundId, @RequestBody(required = false) CancelInput input)
```

### POST `/api/v1/rounds/{roundId}/no-show`

- Handler: `noShow` ([source](../../interview-service/src/main/java/com/openhire/interview/adapter/in/web/InterviewController.java#L142)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.coordinator(auth)`; `viewers.tenant(auth)`.
- Delegated operations: `interviews.noShow`.

Request bindings and declared response:

```java
Map<String, String> noShow(Authentication auth, @PathVariable String roundId)
```

### POST `/api/v1/rounds/{roundId}/complete`

- Handler: `complete` ([source](../../interview-service/src/main/java/com/openhire/interview/adapter/in/web/InterviewController.java#L149)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.coordinator(auth)`; `viewers.tenant(auth)`.
- Delegated operations: `interviews.complete`.

Request bindings and declared response:

```java
Map<String, String> complete(Authentication auth, @PathVariable String roundId)
```

### POST `/api/v1/rounds/{roundId}/scheduling-link`

- Handler: `schedulingLink` ([source](../../interview-service/src/main/java/com/openhire/interview/adapter/in/web/InterviewController.java#L156)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.coordinator(auth)`; `viewers.tenant(auth)`.
- Delegated operations: `interviews.schedulingLink`.

Request bindings and declared response:

```java
SchedulingLinkView schedulingLink(Authentication auth, @PathVariable String roundId)
```

### GET `/api/v1/rounds/{roundId}/scorecard`

- Handler: `scorecard` ([source](../../interview-service/src/main/java/com/openhire/interview/adapter/in/web/InterviewController.java#L162)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.tenant(auth)`; `viewers.participant(auth)`.
- Delegated operations: `interviews.myScorecard`.

Request bindings and declared response:

```java
ScorecardView scorecard(Authentication auth, @PathVariable String roundId)
```

### POST `/api/v1/rounds/{roundId}/scorecards`

- Handler: `submit` ([source](../../interview-service/src/main/java/com/openhire/interview/adapter/in/web/InterviewController.java#L167)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.participant(auth)`; `viewers.tenant(auth)`.
- Delegated operations: `interviews.submitScorecard`.

Request bindings and declared response:

```java
Map<String, String> submit(Authentication auth, @PathVariable String roundId, @RequestBody ScorecardInput input)
```

### GET `/api/v1/rounds/{roundId}/scorecards`

- Handler: `scorecards` ([source](../../interview-service/src/main/java/com/openhire/interview/adapter/in/web/InterviewController.java#L184)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.tenant(auth)`; `viewers.participant(auth)`.
- Delegated operations: `interviews.scorecards`.

Request bindings and declared response:

```java
Map<String, List<SubmittedScorecardView>> scorecards(Authentication auth, @PathVariable String roundId)
```

### GET `/api/v1/applications/{applicationId}/debrief`

- Handler: `debrief` ([source](../../interview-service/src/main/java/com/openhire/interview/adapter/in/web/InterviewController.java#L189)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.tenant(auth)`; `viewers.participant(auth)`.
- Delegated operations: `interviews.debrief`.

Request bindings and declared response:

```java
DebriefBuilder.Debrief debrief(Authentication auth, @PathVariable String applicationId)
```

## PublicSchedulingController

Source: [PublicSchedulingController](../../interview-service/src/main/java/com/openhire/interview/adapter/in/web/PublicSchedulingController.java).

### GET `/api/v1/public/scheduling`

- Handler: `view` ([source](../../interview-service/src/main/java/com/openhire/interview/adapter/in/web/PublicSchedulingController.java#L29)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.
- Delegated operations: `interviews.publicView`.

Request bindings and declared response:

```java
PublicSchedulingView view(@RequestHeader("X-Scheduling-Token") String token)
```

### POST `/api/v1/public/scheduling/book`

- Handler: `book` ([source](../../interview-service/src/main/java/com/openhire/interview/adapter/in/web/PublicSchedulingController.java#L34)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.
- Delegated operations: `interviews.publicBook`.

Request bindings and declared response:

```java
PublicSchedulingView book(@RequestHeader("X-Scheduling-Token") String token, @RequestBody BookInput input)
```

### POST `/api/v1/public/scheduling/alternatives`

- Handler: `alternatives` ([source](../../interview-service/src/main/java/com/openhire/interview/adapter/in/web/PublicSchedulingController.java#L39)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.
- Delegated operations: `interviews.publicAlternatives`.

Request bindings and declared response:

```java
PublicSchedulingView alternatives(@RequestHeader("X-Scheduling-Token") String token)
```

### POST `/api/v1/public/scheduling/cancel`

- Handler: `cancel` ([source](../../interview-service/src/main/java/com/openhire/interview/adapter/in/web/PublicSchedulingController.java#L44)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.
- Delegated operations: `interviews.publicCancel`.

Request bindings and declared response:

```java
PublicSchedulingView cancel( @RequestHeader("X-Scheduling-Token") String token, @RequestBody(required = false) CancelInput input)
```

## Request and response record fields

These are source declarations, including nested DTO records referenced by signatures. Same-named records in different files are scoped to their linked owner. Constructor/domain validation and enum values remain in the linked source; `Map` responses are deliberately not assigned an invented schema.

### ScheduleInput

[Definition](../../interview-service/src/main/java/com/openhire/interview/adapter/in/web/InterviewController.java)

```java
record ScheduleInput(String start, String timezone, List<PanelInput> panel, String meetingUrl)
```

### RescheduleInput

[Definition](../../interview-service/src/main/java/com/openhire/interview/adapter/in/web/InterviewController.java)

```java
record RescheduleInput(String start)
```

### CancelInput

[Definition](../../interview-service/src/main/java/com/openhire/interview/adapter/in/web/InterviewController.java)

```java
record CancelInput(String reason)
```

### ScorecardInput

[Definition](../../interview-service/src/main/java/com/openhire/interview/adapter/in/web/InterviewController.java)

```java
record ScorecardInput(List<RatingInput> ratings, String evidence, String recommendation)
```

### PanelAssignment

[Definition](../../interview-service/src/main/java/com/openhire/interview/adapter/in/web/InterviewController.java)

```java
record PanelAssignment(List<PanelInput> panel)
```

### BookInput

[Definition](../../interview-service/src/main/java/com/openhire/interview/adapter/in/web/PublicSchedulingController.java)

```java
record BookInput(String start)
```

### CancelInput

[Definition](../../interview-service/src/main/java/com/openhire/interview/adapter/in/web/PublicSchedulingController.java)

```java
record CancelInput(String reason)
```

### Page

[Definition](../../interview-service/src/main/java/com/openhire/interview/application/InterviewViews.java)

```java
record Page<T>(List<T> data, String nextCursor, boolean hasMore)
```

### InterviewEventView

[Definition](../../interview-service/src/main/java/com/openhire/interview/application/InterviewViews.java)

```java
record InterviewEventView(
            String id,
            String applicationId,
            String candidateId,
            String candidateName,
            String jobTitle,
            String roundName,
            String start,
            int durationMin,
            String interviewerName,
            String status,
            boolean scorecardSubmitted)
```

### UnscheduledRoundView

[Definition](../../interview-service/src/main/java/com/openhire/interview/application/InterviewViews.java)

```java
record UnscheduledRoundView(
            String roundId,
            String loopId,
            String applicationId,
            String candidateName,
            String jobTitle,
            String roundName,
            int durationMin,
            List<PanelView> panel,
            String status)
```

### RoundView

[Definition](../../interview-service/src/main/java/com/openhire/interview/application/InterviewViews.java)

```java
record RoundView(
            String id,
            int seq,
            String name,
            int durationMin,
            String status,
            String start,
            String end,
            String timezone,
            List<PanelView> panel,
            String meetingUrl,
            int rescheduleCount,
            String feedbackDueAt,
            int scorecardsSubmitted,
            int scorecardsExpected)
```

### LoopView

[Definition](../../interview-service/src/main/java/com/openhire/interview/application/InterviewViews.java)

```java
record LoopView(
            String loopId,
            String applicationId,
            String candidateName,
            String jobTitle,
            boolean closed,
            List<RoundView> rounds)
```

### ScorecardView

[Definition](../../interview-service/src/main/java/com/openhire/interview/application/InterviewViews.java)

```java
record ScorecardView(
            String roundId,
            String candidateName,
            String roundName,
            List<CompetencyView> competencies,
            String evidence,
            boolean submitted,
            List<String> aiSuggestedQuestions)
```

### SubmittedScorecardView

[Definition](../../interview-service/src/main/java/com/openhire/interview/application/InterviewViews.java)

```java
record SubmittedScorecardView(
            String interviewerName,
            List<CompetencyView> ratings,
            String evidence,
            String recommendation,
            String submittedAt)
```

### MyInterviewView

[Definition](../../interview-service/src/main/java/com/openhire/interview/application/InterviewViews.java)

```java
record MyInterviewView(
            String roundId,
            String applicationId,
            String candidateName,
            String jobTitle,
            String roundName,
            String start,
            int durationMin,
            String status,
            String myScorecard,
            String feedbackDueAt)
```

### SchedulingLinkView

[Definition](../../interview-service/src/main/java/com/openhire/interview/application/InterviewViews.java)

```java
record SchedulingLinkView(String url, String expiresAt, List<String> slots)
```

### PublicSchedulingView

[Definition](../../interview-service/src/main/java/com/openhire/interview/application/InterviewViews.java)

```java
record PublicSchedulingView(
            String jobTitle,
            String roundName,
            int durationMin,
            String timezone,
            List<String> slots,
            String holdExpiresAt,
            String bookedStart,
            String status,
            boolean canChange,
            String changeNote)
```

### Debrief

[Definition](../../interview-service/src/main/java/com/openhire/interview/domain/DebriefBuilder.java)

```java
record Debrief(
            String applicationId,
            String candidateName,
            int roundsCompleted,
            int roundsTotal,
            List<Point> consistentStrengths,
            List<Point> concerns,
            List<Point> disagreements,
            String generatedBy)
```

### PanelInput

[Definition](../../interview-service/src/main/java/com/openhire/interview/adapter/in/web/InterviewController.java)

```java
record PanelInput(String name, String email, String role)
```

### RatingInput

[Definition](../../interview-service/src/main/java/com/openhire/interview/adapter/in/web/InterviewController.java)

```java
record RatingInput(String competencyId, String rating)
```

### PanelView

[Definition](../../interview-service/src/main/java/com/openhire/interview/application/InterviewViews.java)

```java
record PanelView(String name, String email, String role)
```

### CompetencyView

[Definition](../../interview-service/src/main/java/com/openhire/interview/application/InterviewViews.java)

```java
record CompetencyView(String id, String label, String rating)
```

### Point

[Definition](../../interview-service/src/main/java/com/openhire/interview/domain/DebriefBuilder.java)

```java
record Point(String text, List<String> fromRounds)
```
