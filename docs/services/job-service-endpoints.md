# job-service — REST endpoint reference

Generated from current Java controller annotations. This is a source-level reference, not proof of deployed reachability or live acceptance. See [service design](job-service.md) for business rules and end-to-end flows, and [shared conventions](END_TO_END_DESIGN.md#api-and-security-conventions).

Mapped operations: **24**. Framework-generated Actuator endpoints and gateway-proxied routes are not duplicated here. Signatures preserve request headers, query defaults, body types, return types and optional bindings. Java types are not JSON field names: request/response record fields are listed below. Controller checks may delegate additional authorization to application services; an empty extracted-check list does not mean public access.

## HiringConfigurationController

Source: [HiringConfigurationController](../../job-service/src/main/java/com/openhire/jobservice/controller/HiringConfigurationController.java).

### GET `/api/v1/hiring-stages`

- Handler: `stages` ([source](../../job-service/src/main/java/com/openhire/jobservice/controller/HiringConfigurationController.java#L28)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER')")`.
- Delegated operations: `service.stages`.

Request bindings and declared response:

```java
List<HiringStageSet> stages()
```

### PUT `/api/v1/hiring-stages`

- Handler: `saveStages` ([source](../../job-service/src/main/java/com/openhire/jobservice/controller/HiringConfigurationController.java#L34)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN')")`.
- Delegated operations: `service.saveStages`.

Request bindings and declared response:

```java
HiringStageSet saveStages(@Valid @RequestBody StageSetRequest request)
```

### GET `/api/v1/pipeline-templates`

- Handler: `templates` ([source](../../job-service/src/main/java/com/openhire/jobservice/controller/HiringConfigurationController.java#L40)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER')")`.
- Delegated operations: `service.templates`.

Request bindings and declared response:

```java
List<PipelineTemplate> templates()
```

### PUT `/api/v1/pipeline-templates`

- Handler: `saveTemplate` ([source](../../job-service/src/main/java/com/openhire/jobservice/controller/HiringConfigurationController.java#L46)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN')")`.
- Delegated operations: `service.saveTemplate`.

Request bindings and declared response:

```java
PipelineTemplate saveTemplate(@Valid @RequestBody TemplateRequest request)
```

### GET `/api/v1/pipeline-templates/resolve`

- Handler: `resolve` ([source](../../job-service/src/main/java/com/openhire/jobservice/controller/HiringConfigurationController.java#L52)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER')")`.
- Delegated operations: `service.resolve`.

Request bindings and declared response:

```java
PipelineTemplate resolve(@RequestParam String key)
```

## JobController

Source: [JobController](../../job-service/src/main/java/com/openhire/jobservice/controller/JobController.java).

### GET `/api/v1/jobs`

- Handler: `list` ([source](../../job-service/src/main/java/com/openhire/jobservice/controller/JobController.java#L45)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER','HIRING_MANAGER','INTERVIEWER')")`.
- Delegated operations: `service.list`.

Request bindings and declared response:

```java
PageResponse<JobSummaryResponse> list(@RequestParam(required = false) String status)
```

### POST `/api/v1/jobs/ai-draft`

- Handler: `draftBeforeCreate` ([source](../../job-service/src/main/java/com/openhire/jobservice/controller/JobController.java#L51)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER')")`.
- Delegated operations: `service.draft`.

Request bindings and declared response:

```java
AiJobDraftResponse draftBeforeCreate(@Valid @RequestBody AiJobDraftRequest request)
```

### GET `/api/v1/jobs/{id}`

- Handler: `get` ([source](../../job-service/src/main/java/com/openhire/jobservice/controller/JobController.java#L57)).
- Success status: ResponseEntity chooses status; see handler.
- Controller access/tenant calls: `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER','HIRING_MANAGER','INTERVIEWER')")`.
- Delegated operations: `service.get`.

Request bindings and declared response:

```java
ResponseEntity<JobResponse> get(@PathVariable String id)
```

### POST `/api/v1/jobs`

- Handler: `create` ([source](../../job-service/src/main/java/com/openhire/jobservice/controller/JobController.java#L63)).
- Success status: ResponseEntity chooses status; see handler.
- Controller access/tenant calls: `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER')")`.
- Delegated operations: `service.create`.

Request bindings and declared response:

```java
ResponseEntity<JobResponse> create( @RequestHeader(value = "X-Actor-Id", defaultValue = "local-admin") String actor, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, @Valid @RequestBody CreateJobRequest request)
```

### PATCH `/api/v1/jobs/{id}`

- Handler: `update` ([source](../../job-service/src/main/java/com/openhire/jobservice/controller/JobController.java#L75)).
- Success status: ResponseEntity chooses status; see handler.
- Controller access/tenant calls: `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER')")`.
- Delegated operations: `service.update`.

Request bindings and declared response:

```java
ResponseEntity<JobResponse> update( @PathVariable String id, @RequestHeader("If-Match") String ifMatch, @RequestHeader(value = "X-Actor-Id", defaultValue = "local-admin") String actor, @Valid @RequestBody CreateJobRequest request)
```

### PUT `/api/v1/jobs/{id}/hiring-team`

- Handler: `updateHiringTeam` ([source](../../job-service/src/main/java/com/openhire/jobservice/controller/JobController.java#L85)).
- Success status: ResponseEntity chooses status; see handler.
- Controller access/tenant calls: `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER')")`.
- Delegated operations: `service.updateHiringTeam`.

Request bindings and declared response:

```java
ResponseEntity<JobResponse> updateHiringTeam( @PathVariable String id, @RequestHeader("If-Match") String ifMatch, @RequestHeader(value = "X-Actor-Id", defaultValue = "local-admin") String actor, @Valid @RequestBody HiringTeamRequest request)
```

### PUT `/api/v1/jobs/{id}/versions/{version}/interview-plan`

- Handler: `updateInterviewPlan` ([source](../../job-service/src/main/java/com/openhire/jobservice/controller/JobController.java#L95)).
- Success status: ResponseEntity chooses status; see handler.
- Controller access/tenant calls: `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER')")`.
- Delegated operations: `service.updateInterviewPlan`.

Request bindings and declared response:

```java
ResponseEntity<JobResponse> updateInterviewPlan( @PathVariable String id, @PathVariable int version, @RequestHeader(value = "X-Actor-Id", defaultValue = "local-admin") String actor, @Valid @RequestBody InterviewPlanRequest request)
```

### POST `/api/v1/jobs/{id}/submit`

- Handler: `submit` ([source](../../job-service/src/main/java/com/openhire/jobservice/controller/JobController.java#L105)).
- Success status: ResponseEntity chooses status; see handler.
- Controller access/tenant calls: `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER')")`.
- Delegated operations: `service.submit`.

Request bindings and declared response:

```java
ResponseEntity<JobResponse> submit( @PathVariable String id, @RequestHeader(value = "X-Actor-Id", defaultValue = "local-admin") String actor)
```

### POST `/api/v1/jobs/{id}/publish`

- Handler: `publish` ([source](../../job-service/src/main/java/com/openhire/jobservice/controller/JobController.java#L112)).
- Success status: ResponseEntity chooses status; see handler.
- Controller access/tenant calls: `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER')")`.
- Delegated operations: `service.publish`.

Request bindings and declared response:

```java
ResponseEntity<JobResponse> publish( @PathVariable String id, @RequestHeader(value = "X-Actor-Id", defaultValue = "local-admin") String actor)
```

### POST `/api/v1/jobs/{id}/pause`

- Handler: `pause` ([source](../../job-service/src/main/java/com/openhire/jobservice/controller/JobController.java#L119)).
- Success status: ResponseEntity chooses status; see handler.
- Controller access/tenant calls: `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER')")`.
- Delegated operations: `service.pause`.

Request bindings and declared response:

```java
ResponseEntity<JobResponse> pause( @PathVariable String id, @RequestHeader(value = "X-Actor-Id", defaultValue = "local-admin") String actor)
```

### POST `/api/v1/jobs/{id}/close`

- Handler: `close` ([source](../../job-service/src/main/java/com/openhire/jobservice/controller/JobController.java#L126)).
- Success status: ResponseEntity chooses status; see handler.
- Controller access/tenant calls: `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER')")`.
- Delegated operations: `service.close`.

Request bindings and declared response:

```java
ResponseEntity<JobResponse> close( @PathVariable String id, @RequestHeader(value = "X-Actor-Id", defaultValue = "local-admin") String actor)
```

### POST `/api/v1/jobs/{id}/reopen`

- Handler: `reopen` ([source](../../job-service/src/main/java/com/openhire/jobservice/controller/JobController.java#L133)).
- Success status: ResponseEntity chooses status; see handler.
- Controller access/tenant calls: `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER')")`.
- Delegated operations: `service.reopen`.

Request bindings and declared response:

```java
ResponseEntity<JobResponse> reopen( @PathVariable String id, @RequestHeader(value = "X-Actor-Id", defaultValue = "local-admin") String actor)
```

### POST `/api/v1/jobs/{id}/versions/{version}/ai-draft`

- Handler: `draft` ([source](../../job-service/src/main/java/com/openhire/jobservice/controller/JobController.java#L140)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER')")`.
- Delegated operations: `service.draft`.

Request bindings and declared response:

```java
AiJobDraftResponse draft( @PathVariable String id, @PathVariable int version, @RequestBody(required = false) java.util.Map<String, String> request)
```

### POST `/api/v1/jobs/internal/{id}/approval-decision`

- Handler: `approvalDecision` ([source](../../job-service/src/main/java/com/openhire/jobservice/controller/JobController.java#L152)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.
- Delegated operations: `service.decision`.

Request bindings and declared response:

```java
JobResponse approvalDecision( @PathVariable String id, @RequestHeader("X-Internal-Service-Secret") String secret, @Valid @RequestBody DecisionRequest request)
```

### GET `/api/v1/jobs/internal/{id}/intake`

- Handler: `intakeSnapshot` ([source](../../job-service/src/main/java/com/openhire/jobservice/controller/JobController.java#L161)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.
- Delegated operations: `service.get`.

Request bindings and declared response:

```java
JobResponse intakeSnapshot(@PathVariable String id, @RequestHeader("X-Internal-Service-Secret") String secret)
```

## JobProjectionController

Source: [JobProjectionController](../../job-service/src/main/java/com/openhire/jobservice/controller/JobProjectionController.java).

### GET `/api/v1/public/jobs`

- Handler: `publicJobs` ([source](../../job-service/src/main/java/com/openhire/jobservice/controller/JobProjectionController.java#L23)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.

Request bindings and declared response:

```java
Object publicJobs(@RequestParam String tenantId)
```

### GET `/api/v1/public/jobs/{id}`

- Handler: `publicJob` ([source](../../job-service/src/main/java/com/openhire/jobservice/controller/JobProjectionController.java#L28)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.

Request bindings and declared response:

```java
Object publicJob(@RequestParam String tenantId, @PathVariable String id)
```

### GET `/api/v1/internal-jobs`

- Handler: `internalJobs` ([source](../../job-service/src/main/java/com/openhire/jobservice/controller/JobProjectionController.java#L50)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.

Request bindings and declared response:

```java
Object internalJobs()
```

### GET `/api/v1/me/inbox`

- Handler: `today` ([source](../../job-service/src/main/java/com/openhire/jobservice/controller/JobProjectionController.java#L55)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.

Request bindings and declared response:

```java
Map<String, Object> today()
```

## Request and response record fields

These are source declarations, including nested DTO records referenced by signatures. Same-named records in different files are scoped to their linked owner. Constructor/domain validation and enum values remain in the linked source; `Map` responses are deliberately not assigned an invented schema.

### HiringStageSet

[Definition](../../job-service/src/main/java/com/openhire/jobservice/configuration/HiringConfiguration.java)

```java
record HiringStageSet(
            @Id String id, String tenantId, String key, String name, List<Stage> stages, Instant updatedAt)
```

### Stage

[Definition](../../job-service/src/main/java/com/openhire/jobservice/configuration/HiringConfiguration.java)

```java
record Stage(String id, String name, String type, int position, boolean terminal)
```

### PipelineTemplate

[Definition](../../job-service/src/main/java/com/openhire/jobservice/configuration/HiringConfiguration.java)

```java
record PipelineTemplate(
            @Id String id, String tenantId, String key, String name, List<String> stageIds, Instant updatedAt)
```

### AiJobDraftRequest

[Definition](../../job-service/src/main/java/com/openhire/jobservice/dto/request/AiJobDraftRequest.java)

```java
record AiJobDraftRequest(
        @NotBlank String title, String team, String level, List<String> mustHaveSkills, String tone)
```

### CreateJobRequest

[Definition](../../job-service/src/main/java/com/openhire/jobservice/dto/request/CreateJobRequest.java)

```java
record CreateJobRequest(
        @NotBlank String title,
        @NotBlank String team,
        @NotBlank String level,
        @NotBlank String location,
        SalaryBand salaryBand,
        @NotEmpty List<@NotBlank String> mustHaveSkills,
        List<KnockoutQuestion> knockoutQuestions,
        List<RubricCriterion> rubric,
        @NotBlank String description,
        String status)
```

### SalaryBand

[Definition](../../job-service/src/main/java/com/openhire/jobservice/dto/request/CreateJobRequest.java)

```java
record SalaryBand(@NotBlank String currency, BigDecimal min, BigDecimal max)
```

### KnockoutQuestion

[Definition](../../job-service/src/main/java/com/openhire/jobservice/dto/request/CreateJobRequest.java)

```java
record KnockoutQuestion(@NotBlank String id, @NotBlank String question, @NotBlank String requiredAnswer)
```

### RubricCriterion

[Definition](../../job-service/src/main/java/com/openhire/jobservice/dto/request/CreateJobRequest.java)

```java
record RubricCriterion(@NotBlank String id, @NotBlank String label, int weight)
```

### HiringTeamRequest

[Definition](../../job-service/src/main/java/com/openhire/jobservice/dto/request/JobCommands.java)

```java
record HiringTeamRequest(List<@Valid HiringTeamMember> hiringTeam)
```

### HiringTeamMember

[Definition](../../job-service/src/main/java/com/openhire/jobservice/dto/request/JobCommands.java)

```java
record HiringTeamMember(@NotBlank String userId, @NotBlank String name, @NotBlank String role)
```

### InterviewPlanRequest

[Definition](../../job-service/src/main/java/com/openhire/jobservice/dto/request/JobCommands.java)

```java
record InterviewPlanRequest(List<@Valid InterviewRound> interviewPlan)
```

### InterviewRound

[Definition](../../job-service/src/main/java/com/openhire/jobservice/dto/request/JobCommands.java)

```java
record InterviewRound(
            @NotBlank String id, @NotBlank String name, int durationMin, List<@NotBlank String> interviewerNames)
```

### DecisionRequest

[Definition](../../job-service/src/main/java/com/openhire/jobservice/dto/request/JobCommands.java)

```java
record DecisionRequest(@NotBlank String decision, String reason, String actorId)
```

### StageSetRequest

[Definition](../../job-service/src/main/java/com/openhire/jobservice/dto/request/StageSetRequest.java)

```java
record StageSetRequest(@NotBlank String key, @NotBlank String name, List<@Valid Stage> stages)
```

### TemplateRequest

[Definition](../../job-service/src/main/java/com/openhire/jobservice/dto/request/TemplateRequest.java)

```java
record TemplateRequest(@NotBlank String key, @NotBlank String name, List<@NotBlank String> stageIds)
```

### AiJobDraftResponse

[Definition](../../job-service/src/main/java/com/openhire/jobservice/dto/response/AiJobDraftResponse.java)

```java
record AiJobDraftResponse(
        String description, List<String> biasFlags, List<InterviewRound> suggestedInterviewPlan, String model)
```

### InterviewRound

[Definition](../../job-service/src/main/java/com/openhire/jobservice/dto/response/AiJobDraftResponse.java)

```java
record InterviewRound(String id, String name, int durationMin, List<String> interviewerNames)
```

### JobResponse

[Definition](../../job-service/src/main/java/com/openhire/jobservice/dto/response/JobResponse.java)

```java
record JobResponse(
        String id,
        String code,
        String title,
        String team,
        String location,
        JobStatus status,
        int applicants,
        int shortlisted,
        int daysOpen,
        String hiringManager,
        Funnel funnel,
        long revision,
        int approvedVersion,
        String level,
        SalaryBand salaryBand,
        JobVersion currentVersion,
        List<HiringTeamMember> hiringTeam,
        List<Object> channels,
        List<Activity> activity)
```

### Funnel

[Definition](../../job-service/src/main/java/com/openhire/jobservice/dto/response/JobResponse.java)

```java
record Funnel(int applied, int screened, int shortlisted, int interviewing, int offer, int hired)
```

### SalaryBand

[Definition](../../job-service/src/main/java/com/openhire/jobservice/dto/response/JobResponse.java)

```java
record SalaryBand(String currency, BigDecimal min, BigDecimal max)
```

### KnockoutQuestion

[Definition](../../job-service/src/main/java/com/openhire/jobservice/dto/response/JobResponse.java)

```java
record KnockoutQuestion(String id, String question, String requiredAnswer)
```

### RubricCriterion

[Definition](../../job-service/src/main/java/com/openhire/jobservice/dto/response/JobResponse.java)

```java
record RubricCriterion(String id, String label, int weight)
```

### InterviewRound

[Definition](../../job-service/src/main/java/com/openhire/jobservice/dto/response/JobResponse.java)

```java
record InterviewRound(String id, String name, int durationMin, List<String> interviewerNames)
```

### HiringTeamMember

[Definition](../../job-service/src/main/java/com/openhire/jobservice/dto/response/JobResponse.java)

```java
record HiringTeamMember(String userId, String name, String role)
```

### JobVersion

[Definition](../../job-service/src/main/java/com/openhire/jobservice/dto/response/JobResponse.java)

```java
record JobVersion(
            int version,
            String status,
            String description,
            List<String> mustHaveSkills,
            List<KnockoutQuestion> knockoutQuestions,
            List<RubricCriterion> rubric,
            List<InterviewRound> interviewPlan)
```

### Activity

[Definition](../../job-service/src/main/java/com/openhire/jobservice/dto/response/JobResponse.java)

```java
record Activity(String id, Instant at, String actor, String text)
```

### JobSummaryResponse

[Definition](../../job-service/src/main/java/com/openhire/jobservice/dto/response/JobSummaryResponse.java)

```java
record JobSummaryResponse(
        String id,
        String code,
        String title,
        String team,
        String location,
        JobStatus status,
        int applicants,
        int shortlisted,
        int daysOpen,
        String hiringManager,
        JobResponse.Funnel funnel)
```

### PageResponse

[Definition](../../job-service/src/main/java/com/openhire/jobservice/dto/response/PageResponse.java)

```java
record PageResponse<T>(List<T> data, String nextCursor, boolean hasMore)
```

### JobVersion

[Definition](../../job-service/src/main/java/com/openhire/jobservice/domain/Job.java)

```java
record JobVersion(
            int version,
            String description,
            List<String> mustHaveSkills,
            List<KnockoutQuestion> knockoutQuestions,
            List<RubricCriterion> rubric,
            List<InterviewRound> interviewPlan,
            Instant createdAt)
```

### KnockoutQuestion

[Definition](../../job-service/src/main/java/com/openhire/jobservice/domain/Job.java)

```java
record KnockoutQuestion(String id, String question, String requiredAnswer)
```

### RubricCriterion

[Definition](../../job-service/src/main/java/com/openhire/jobservice/domain/Job.java)

```java
record RubricCriterion(String id, String label, int weight)
```

### InterviewRound

[Definition](../../job-service/src/main/java/com/openhire/jobservice/domain/Job.java)

```java
record InterviewRound(String id, String name, int durationMin, List<String> interviewerNames)
```

### HiringTeamMember

[Definition](../../job-service/src/main/java/com/openhire/jobservice/domain/Job.java)

```java
record HiringTeamMember(String userId, String name, String role)
```

### Activity

[Definition](../../job-service/src/main/java/com/openhire/jobservice/domain/Job.java)

```java
record Activity(String id, Instant at, String actor, String text)
```
