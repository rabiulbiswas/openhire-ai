# offer-service — REST endpoint reference

Generated from current Java controller annotations. This is a source-level reference, not proof of deployed reachability or live acceptance. See [service design](offer-service.md) for business rules and end-to-end flows, and [shared conventions](END_TO_END_DESIGN.md#api-and-security-conventions).

Mapped operations: **19**. Framework-generated Actuator endpoints and gateway-proxied routes are not duplicated here. Signatures preserve request headers, query defaults, body types, return types and optional bindings. Java types are not JSON field names: request/response record fields are listed below. Controller checks may delegate additional authorization to application services; an empty extracted-check list does not mean public access.

## OfferController

Source: [OfferController](../../offer-service/src/main/java/com/openhire/offer/adapter/in/web/OfferController.java).

### GET `/api/v1/offers`

- Handler: `list` ([source](../../offer-service/src/main/java/com/openhire/offer/adapter/in/web/OfferController.java#L45)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.tenant(auth)`; `viewers.viewer(auth)`.
- Delegated operations: `offers.list`.

Request bindings and declared response:

```java
Page<OfferSummary> list(Authentication auth, @RequestParam(required = false) String status)
```

### GET `/api/v1/offers/{id}`

- Handler: `get` ([source](../../offer-service/src/main/java/com/openhire/offer/adapter/in/web/OfferController.java#L50)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.tenant(auth)`; `viewers.viewer(auth)`.
- Delegated operations: `offers.get`.

Request bindings and declared response:

```java
OfferDetail get(Authentication auth, @PathVariable String id)
```

### POST `/api/v1/applications/{applicationId}/offer`

- Handler: `create` ([source](../../offer-service/src/main/java/com/openhire/offer/adapter/in/web/OfferController.java#L55)).
- Success status: \@ResponseStatus(HttpStatus.CREATED).
- Controller access/tenant calls: `viewers.tenant(auth)`; `viewers.viewer(auth)`.
- Delegated operations: `offers.create`.

Request bindings and declared response:

```java
OfferDetail create( Authentication auth, @PathVariable String applicationId, @RequestBody(required = false) CreateInput input)
```

### GET `/api/v1/applications/{applicationId}/offer`

- Handler: `forApplication` ([source](../../offer-service/src/main/java/com/openhire/offer/adapter/in/web/OfferController.java#L69)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.tenant(auth)`; `viewers.viewer(auth)`.
- Delegated operations: `offers.forApplication`.

Request bindings and declared response:

```java
OfferDetail forApplication(Authentication auth, @PathVariable String applicationId)
```

### PATCH `/api/v1/offers/{id}`

- Handler: `update` ([source](../../offer-service/src/main/java/com/openhire/offer/adapter/in/web/OfferController.java#L74)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.tenant(auth)`; `viewers.viewer(auth)`.
- Delegated operations: `offers.update`.

Request bindings and declared response:

```java
OfferDetail update(Authentication auth, @PathVariable String id, @RequestBody UpdateInput input)
```

### POST `/api/v1/offers/{id}/submit`

- Handler: `submit` ([source](../../offer-service/src/main/java/com/openhire/offer/adapter/in/web/OfferController.java#L79)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.tenant(auth)`; `viewers.viewer(auth)`.
- Delegated operations: `offers.submit`.

Request bindings and declared response:

```java
OfferDetail submit(Authentication auth, @PathVariable String id)
```

### POST `/api/v1/offers/{id}/approvals/{stepId}/approve`

- Handler: `approve` ([source](../../offer-service/src/main/java/com/openhire/offer/adapter/in/web/OfferController.java#L84)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.tenant(auth)`; `viewers.viewer(auth)`.
- Delegated operations: `offers.approve`.

Request bindings and declared response:

```java
OfferDetail approve(Authentication auth, @PathVariable String id, @PathVariable String stepId)
```

### POST `/api/v1/offers/{id}/approvals/{stepId}/reject`

- Handler: `reject` ([source](../../offer-service/src/main/java/com/openhire/offer/adapter/in/web/OfferController.java#L89)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.tenant(auth)`; `viewers.viewer(auth)`.
- Delegated operations: `offers.reject`.

Request bindings and declared response:

```java
OfferDetail reject( Authentication auth, @PathVariable String id, @PathVariable String stepId, @RequestBody(required = false) ReasonInput input)
```

### POST `/api/v1/offers/{id}/send`

- Handler: `send` ([source](../../offer-service/src/main/java/com/openhire/offer/adapter/in/web/OfferController.java#L99)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.tenant(auth)`; `viewers.viewer(auth)`.
- Delegated operations: `offers.send`.

Request bindings and declared response:

```java
OfferDetail send(Authentication auth, @PathVariable String id)
```

### POST `/api/v1/offers/{id}/rescind`

- Handler: `rescind` ([source](../../offer-service/src/main/java/com/openhire/offer/adapter/in/web/OfferController.java#L104)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.tenant(auth)`; `viewers.viewer(auth)`.
- Delegated operations: `offers.rescind`.

Request bindings and declared response:

```java
OfferDetail rescind( Authentication auth, @PathVariable String id, @RequestBody(required = false) ReasonInput input)
```

### GET `/api/v1/offer-templates`

- Handler: `templates` ([source](../../offer-service/src/main/java/com/openhire/offer/adapter/in/web/OfferController.java#L110)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.tenant(auth)`; `viewers.viewer(auth)`.
- Delegated operations: `offers.templates`.

Request bindings and declared response:

```java
Page<TemplateView> templates(Authentication auth)
```

### PUT `/api/v1/offer-templates/{key}`

- Handler: `saveTemplate` ([source](../../offer-service/src/main/java/com/openhire/offer/adapter/in/web/OfferController.java#L115)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.tenant(auth)`; `viewers.viewer(auth)`.
- Delegated operations: `offers.saveTemplate`.

Request bindings and declared response:

```java
TemplateView saveTemplate(Authentication auth, @PathVariable String key, @RequestBody TemplateInput input)
```

### GET `/api/v1/offer-clauses`

- Handler: `clauses` ([source](../../offer-service/src/main/java/com/openhire/offer/adapter/in/web/OfferController.java#L121)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.tenant(auth)`; `viewers.viewer(auth)`.
- Delegated operations: `offers.clauses`.

Request bindings and declared response:

```java
Page<ClauseView> clauses(Authentication auth)
```

### PUT `/api/v1/offer-clauses/{key}`

- Handler: `saveClause` ([source](../../offer-service/src/main/java/com/openhire/offer/adapter/in/web/OfferController.java#L126)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: `viewers.tenant(auth)`; `viewers.viewer(auth)`.
- Delegated operations: `offers.saveClause`.

Request bindings and declared response:

```java
ClauseView saveClause(Authentication auth, @PathVariable String key, @RequestBody ClauseInput input)
```

## PublicOfferController

Source: [PublicOfferController](../../offer-service/src/main/java/com/openhire/offer/adapter/in/web/PublicOfferController.java).

### GET `/api/v1/public/offers`

- Handler: `view` ([source](../../offer-service/src/main/java/com/openhire/offer/adapter/in/web/PublicOfferController.java#L34)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.
- Delegated operations: `offers.publicView`.

Request bindings and declared response:

```java
PublicOfferView view(@RequestHeader("X-Offer-Token") String token)
```

### POST `/api/v1/public/offers/accept`

- Handler: `accept` ([source](../../offer-service/src/main/java/com/openhire/offer/adapter/in/web/PublicOfferController.java#L39)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.
- Delegated operations: `offers.publicAccept`.

Request bindings and declared response:

```java
PublicOfferView accept(@RequestHeader("X-Offer-Token") String token, @RequestBody AcceptInput input)
```

### POST `/api/v1/public/offers/decline`

- Handler: `decline` ([source](../../offer-service/src/main/java/com/openhire/offer/adapter/in/web/PublicOfferController.java#L44)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.
- Delegated operations: `offers.publicDecline`.

Request bindings and declared response:

```java
PublicOfferView decline( @RequestHeader("X-Offer-Token") String token, @RequestBody(required = false) DeclineInput input)
```

### POST `/api/v1/public/offers/negotiate`

- Handler: `negotiate` ([source](../../offer-service/src/main/java/com/openhire/offer/adapter/in/web/PublicOfferController.java#L50)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.
- Delegated operations: `offers.publicNegotiate`.

Request bindings and declared response:

```java
PublicOfferView negotiate(@RequestHeader("X-Offer-Token") String token, @RequestBody NegotiateInput input)
```

### GET `/api/v1/public/applications/{applicationId}/offer`

- Handler: `byReceipt` ([source](../../offer-service/src/main/java/com/openhire/offer/adapter/in/web/PublicOfferController.java#L55)).
- Success status: 200 (normal return; exceptions may differ).
- Controller access/tenant calls: See handler and service authorization; no simple access helper extracted.
- Delegated operations: `offers.receiptStatus`.

Request bindings and declared response:

```java
ReceiptOfferView byReceipt( @PathVariable String applicationId, @RequestParam String tenantId, @RequestHeader("X-Application-Receipt") String receipt)
```

## Request and response record fields

These are source declarations, including nested DTO records referenced by signatures. Same-named records in different files are scoped to their linked owner. Constructor/domain validation and enum values remain in the linked source; `Map` responses are deliberately not assigned an invented schema.

### CreateInput

[Definition](../../offer-service/src/main/java/com/openhire/offer/adapter/in/web/OfferController.java)

```java
record CreateInput(String templateKey, TermsInput terms, String coverNote)
```

### UpdateInput

[Definition](../../offer-service/src/main/java/com/openhire/offer/adapter/in/web/OfferController.java)

```java
record UpdateInput(TermsInput terms, String coverNote)
```

### ReasonInput

[Definition](../../offer-service/src/main/java/com/openhire/offer/adapter/in/web/OfferController.java)

```java
record ReasonInput(String reason)
```

### TemplateInput

[Definition](../../offer-service/src/main/java/com/openhire/offer/adapter/in/web/OfferController.java)

```java
record TemplateInput(String name, String body, List<String> clauseKeys)
```

### ClauseInput

[Definition](../../offer-service/src/main/java/com/openhire/offer/adapter/in/web/OfferController.java)

```java
record ClauseInput(String title, String body)
```

### AcceptInput

[Definition](../../offer-service/src/main/java/com/openhire/offer/adapter/in/web/PublicOfferController.java)

```java
record AcceptInput(String fullName)
```

### DeclineInput

[Definition](../../offer-service/src/main/java/com/openhire/offer/adapter/in/web/PublicOfferController.java)

```java
record DeclineInput(String reason)
```

### NegotiateInput

[Definition](../../offer-service/src/main/java/com/openhire/offer/adapter/in/web/PublicOfferController.java)

```java
record NegotiateInput(String message)
```

### TermsInput

[Definition](../../offer-service/src/main/java/com/openhire/offer/application/OfferApplicationService.java)

```java
record TermsInput(
            String currency,
            BigDecimal base,
            BigDecimal variable,
            BigDecimal bonus,
            String joiningDate,
            String joiningDateNote,
            String reportsTo,
            String location,
            String expiryDate,
            String designation)
```

### Page

[Definition](../../offer-service/src/main/java/com/openhire/offer/application/OfferViews.java)

```java
record Page<T>(List<T> data, String nextCursor, boolean hasMore)
```

### OfferSummary

[Definition](../../offer-service/src/main/java/com/openhire/offer/application/OfferViews.java)

```java
record OfferSummary(
            String id,
            String applicationId,
            String candidateId,
            String candidateName,
            String jobId,
            String jobTitle,
            String status,
            String detail,
            int version)
```

### OfferDetail

[Definition](../../offer-service/src/main/java/com/openhire/offer/application/OfferViews.java)

```java
record OfferDetail(
            String id,
            String applicationId,
            String candidateId,
            String candidateName,
            String jobId,
            String jobTitle,
            String status,
            String detail,
            int version,
            List<StepView> approvalSteps,
            String approvalNote,
            String templateName,
            String coverNote,
            TermsView terms,
            BandView band,
            String expiresAt,
            String sentAt,
            String respondedAt,
            String candidateNote,
            List<VersionView> versions)
```

### PublicOfferView

[Definition](../../offer-service/src/main/java/com/openhire/offer/application/OfferViews.java)

```java
record PublicOfferView(
            String jobTitle,
            String companyName,
            String candidateName,
            String status,
            PublicTerms terms,
            String coverNote,
            String letterText,
            String expiresAt,
            String respondedAt,
            boolean canRespond,
            boolean revising)
```

### ReceiptOfferView

[Definition](../../offer-service/src/main/java/com/openhire/offer/application/OfferViews.java)

```java
record ReceiptOfferView(String status, String offerUrl)
```

### TemplateView

[Definition](../../offer-service/src/main/java/com/openhire/offer/application/OfferViews.java)

```java
record TemplateView(
            String key,
            String name,
            String country,
            String level,
            int version,
            String body,
            List<String> clauseKeys,
            String updatedAt)
```

### ClauseView

[Definition](../../offer-service/src/main/java/com/openhire/offer/application/OfferViews.java)

```java
record ClauseView(String key, String title, String body, int version, String updatedAt)
```

### StepView

[Definition](../../offer-service/src/main/java/com/openhire/offer/application/OfferViews.java)

```java
record StepView(
            String id, String approver, String role, String status, String at, String decidedBy, String comment)
```

### TermsView

[Definition](../../offer-service/src/main/java/com/openhire/offer/application/OfferViews.java)

```java
record TermsView(
            String currency,
            BigDecimal base,
            BigDecimal variable,
            BigDecimal bonus,
            String joiningDate,
            String joiningDateNote,
            String reportsTo,
            String location,
            String expiryDate,
            boolean withinBand,
            String designation)
```

### BandView

[Definition](../../offer-service/src/main/java/com/openhire/offer/application/OfferViews.java)

```java
record BandView(String currency, BigDecimal min, BigDecimal max)
```

### VersionView

[Definition](../../offer-service/src/main/java/com/openhire/offer/application/OfferViews.java)

```java
record VersionView(int versionNo, String createdAt, String createdBy, String sha256, boolean sent)
```

### PublicTerms

[Definition](../../offer-service/src/main/java/com/openhire/offer/application/OfferViews.java)

```java
record PublicTerms(
            String currency,
            BigDecimal base,
            BigDecimal variable,
            BigDecimal bonus,
            String joiningDate,
            String location,
            String designation,
            String reportsTo,
            String expiryDate)
```
