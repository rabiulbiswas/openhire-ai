# employee-service — REST endpoint reference

Generated from current Java controller annotations. This is a source-level reference, not proof of deployed reachability or live acceptance. See [service design](employee-service.md) for business rules and end-to-end flows, and [shared conventions](END_TO_END_DESIGN.md#api-and-security-conventions).

Mapped operations: **10**. Framework-generated Actuator endpoints and gateway-proxied routes are not duplicated here. Signatures preserve request headers, query defaults, body types, return types and optional bindings. Java types are not JSON field names: request/response record fields are listed below. Controller checks may delegate additional authorization to application services; an empty extracted-check list does not mean public access.

## EmployeeController

Source: [EmployeeController](../../employee-service/src/main/java/com/openhire/employee/controller/EmployeeController.java).

### GET `/api/v1/employees`

- Handler: `list` ([source](../../employee-service/src/main/java/com/openhire/employee/controller/EmployeeController.java#L44)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.staff(auth)`; `viewers.tenant(auth)`.
- Delegated operations: `employees.list`.

Request bindings and declared response:

```java
Page<EmployeeSummary> list( Authentication auth, @RequestParam(required = false) String status, @RequestParam(required = false) String joiningFrom)
```

### GET `/api/v1/employees/{id}`

- Handler: `detail` ([source](../../employee-service/src/main/java/com/openhire/employee/controller/EmployeeController.java#L53)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.staff(auth)`; `viewers.tenant(auth)`.
- Delegated operations: `employees.detail`.

Request bindings and declared response:

```java
EmployeeDetail detail(Authentication auth, @PathVariable String id)
```

### POST `/api/v1/employees/{id}/activate`

- Handler: `activate` ([source](../../employee-service/src/main/java/com/openhire/employee/controller/EmployeeController.java#L59)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.staff(auth)`; `viewers.tenant(auth)`.
- Delegated operations: `employees.activate`.

Request bindings and declared response:

```java
EmployeeDetail activate(Authentication auth, @PathVariable String id)
```

### GET `/api/v1/employees/{id}/onboarding`

- Handler: `onboarding` ([source](../../employee-service/src/main/java/com/openhire/employee/controller/EmployeeController.java#L65)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.tenant(auth)`; `viewers.viewer(auth)`.
- Delegated operations: `employees.onboarding`.

Request bindings and declared response:

```java
OnboardingView onboarding(Authentication auth, @PathVariable String id)
```

### PATCH `/api/v1/employees/{id}/onboarding/tasks/{taskId}`

- Handler: `toggle` ([source](../../employee-service/src/main/java/com/openhire/employee/controller/EmployeeController.java#L70)).
- Success status: \@ResponseStatus(HttpStatus.NO_CONTENT).
- Controller access/tenant calls: `viewers.tenant(auth)`; `viewers.viewer(auth)`.
- Delegated operations: `employees.toggleTask`.

Request bindings and declared response:

```java
void toggle( Authentication auth, @PathVariable String id, @PathVariable String taskId, @RequestBody TaskInput body)
```

### GET `/api/v1/me/onboarding`

- Handler: `mine` ([source](../../employee-service/src/main/java/com/openhire/employee/controller/EmployeeController.java#L77)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.tenant(auth)`; `viewers.email(auth)`.
- Delegated operations: `employees.mine`.

Request bindings and declared response:

```java
MyOnboardingView mine(Authentication auth)
```

### PATCH `/api/v1/me/onboarding/tasks/{taskId}`

- Handler: `toggleMine` ([source](../../employee-service/src/main/java/com/openhire/employee/controller/EmployeeController.java#L82)).
- Success status: \@ResponseStatus(HttpStatus.NO_CONTENT).
- Controller access/tenant calls: `viewers.tenant(auth)`; `viewers.email(auth)`.
- Delegated operations: `employees.toggleMine`.

Request bindings and declared response:

```java
void toggleMine(Authentication auth, @PathVariable String taskId, @RequestBody TaskInput body)
```

### GET `/api/v1/hire-sagas`

- Handler: `sagas` ([source](../../employee-service/src/main/java/com/openhire/employee/controller/EmployeeController.java#L88)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.requireOperator(auth)`; `viewers.tenant(auth)`.
- Delegated operations: `employees.sagas`.

Request bindings and declared response:

```java
Page<SagaView> sagas(Authentication auth, @RequestParam(required = false) String status)
```

### POST `/api/v1/hire-sagas/{id}/retry`

- Handler: `retry` ([source](../../employee-service/src/main/java/com/openhire/employee/controller/EmployeeController.java#L94)).
- Success status: \@ResponseStatus(HttpStatus.NO_CONTENT).
- Controller access/tenant calls: `viewers.requireOperator(auth)`; `viewers.tenant(auth)`; `viewers.viewer(auth)`.
- Delegated operations: `sagas.retry`.

Request bindings and declared response:

```java
void retry(Authentication auth, @PathVariable String id)
```

### GET `/api/v1/me/referral-rewards`

- Handler: `rewards` ([source](../../employee-service/src/main/java/com/openhire/employee/controller/EmployeeController.java#L101)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.tenant(auth)`; `viewers.viewer(auth)`.
- Delegated operations: `employees.rewards`.

Request bindings and declared response:

```java
Page<RewardView> rewards(Authentication auth)
```

## Request and response record fields

These are source declarations, including nested DTO records referenced by signatures. Same-named records in different files are scoped to their linked owner. Constructor/domain validation and enum values remain in the linked source; `Map` responses are deliberately not assigned an invented schema.

### TaskInput

[Definition](../../employee-service/src/main/java/com/openhire/employee/controller/EmployeeController.java)

```java
record TaskInput(Boolean done)
```

### Page

[Definition](../../employee-service/src/main/java/com/openhire/employee/dto/EmployeeViews.java)

```java
record Page<T>(List<T> data, String nextCursor, boolean hasMore)
```

### EmployeeSummary

[Definition](../../employee-service/src/main/java/com/openhire/employee/dto/EmployeeViews.java)

```java
record EmployeeSummary(
            String id,
            String name,
            String employeeCode,
            String role,
            String joiningDate,
            int onboardingPct,
            String hrSyncStatus)
```

### EmployeeDetail

[Definition](../../employee-service/src/main/java/com/openhire/employee/dto/EmployeeViews.java)

```java
record EmployeeDetail(
            String id,
            String name,
            String employeeCode,
            String role,
            String joiningDate,
            int onboardingPct,
            String hrSyncStatus,
            String status,
            String department,
            String designation,
            String location,
            String managerName,
            String offerId,
            String applicationId,
            String sagaStatus)
```

### OnboardingView

[Definition](../../employee-service/src/main/java/com/openhire/employee/dto/EmployeeViews.java)

```java
record OnboardingView(String employeeId, List<TaskView> tasks)
```

### MyOnboardingView

[Definition](../../employee-service/src/main/java/com/openhire/employee/dto/EmployeeViews.java)

```java
record MyOnboardingView(
            String employeeId,
            String employeeNo,
            String joiningDate,
            String name,
            Contact buddy,
            Contact hrContact,
            List<TaskView> tasks)
```

### SagaView

[Definition](../../employee-service/src/main/java/com/openhire/employee/dto/EmployeeViews.java)

```java
record SagaView(
            String id,
            String offerId,
            String applicationId,
            String candidateName,
            String status,
            String step,
            String lastError,
            String updatedAt,
            boolean stuck,
            int attempts,
            String hrisStatus,
            String employeeNo,
            String employeeId)
```

### RewardView

[Definition](../../employee-service/src/main/java/com/openhire/employee/dto/EmployeeViews.java)

```java
record RewardView(
            String id,
            String applicationId,
            String status,
            String eligibleAt,
            String jobTitle,
            String candidateName,
            String joiningDate)
```

### Contact

[Definition](../../employee-service/src/main/java/com/openhire/employee/domain/EmployeeTypes.java)

```java
record Contact(String name, String email)
```

### TaskView

[Definition](../../employee-service/src/main/java/com/openhire/employee/dto/EmployeeViews.java)

```java
record TaskView(String id, String label, boolean done, String group, String doneAt)
```
