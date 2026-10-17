# Phase 6 — local AI matching, talent and integrity

## Status — 2026-10-15

**Complete and runtime-accepted on the local stack with a real local model.** The sections up to *Deviations from
the plan* are the design and contract the services and UI were built against; *As built* records what differs,
*Open items* what needs an owner decision, and *Evidence* what was run, with the measured numbers.

## Principles (from the HLD guardrails)

- **AI recommends, people decide.** Nothing in this phase moves an application to SHORTLISTED, REJECTED or beyond. AI
  output is an assessment, a draft, a signal or a ranking, always labelled as such.
- **Local data sovereignty.** The default and only provider in this phase is a local model server (Ollama on the
  host; any OpenAI-compatible endpoint also works). Candidate data never leaves the machine unless a tenant
  explicitly configures a hosted provider, and `localOnly` (default on) forbids that.
- **Evidence or nothing.** Every criterion score must cite quotes that exist in the resume chunks the model was shown;
  otherwise the criterion is "insufficient evidence" and does not count as AI support.
- **Fail soft.** If the model is down, slow, returns invalid JSON or is over budget, matching falls back to the
  existing rules and lexical screening, marks the assessment `PARTIAL` ("AI unavailable, partial assessment") and
  re-queues it.

## Local model setup (done on the development machine)

Ollama 0.40 runs on the host (`ollama serve`, port 11434, Apple M3 GPU, about 38 tokens/s for the 3B model).
Models: `qwen2.5:3b-instruct` (about 1.9 GB, text and JSON) and `nomic-embed-text` (about 0.3 GB, 768-dimension
embeddings). Containers reach it at `http://host.docker.internal:11434`. A 3B model is small: expect modest scoring
quality, which is exactly what the evaluation harness and its gates are for. Larger models can be selected per task
in *AI models and budget* without code changes.

## Services

| Service | Container port (host under compose) | Database | Topics | Responsibility |
|---|---|---|---|---|
| `ai-gateway` | 8092 (8097) | `oh_ai` + Redis | `ai.events.v1` | Provider SPI and task routing, prompt registry, input and output guards, cache, cost and budget, provenance, evaluation harness, AI endpoints |
| `ai-workers` | 8093 (8098) | `oh_ai` | `assessment.events.v1`, `integrity.events.v1` | Matching orchestrator (five stages), embeddings of resume chunks, integrity detectors |
| `talent-service` | 8094 (8099) | `oh_talent` | `talent.events.v1` | Talent profiles and vectors, rediscovery, natural-language search, pool health, invitations, talent-pool consent |

Deviations kept deliberately small: the integrity detectors run inside `ai-workers` (a separate package and Kafka
consumer group) rather than a fourth JVM, because the local Docker VM is already near its memory limit; vectors are
stored in MongoDB and searched with exact cosine similarity inside the service (a vector database arrives when the
pool outgrows memory); the provider layer is a small own SPI (Ollama native API and OpenAI-compatible API) instead of
Spring AI, which would add a large dependency tree for two HTTP calls.

All three follow the Phase 4/5 layout: hexagonal packages (`domain`, `application` with `port`, `adapter` in/out,
`config`), `service-support` security and embedded-outbox relay (`_events` array on the aggregate document), ArchUnit
`ArchitectureTest`, in-memory ports in unit tests, opt-in Mongo tests behind `OPENHIRE_RUN_MONGO_TESTS`. Compose
entries (profile `phase6`), the gateway routes and the launcher (`./run-backend.sh phase6`) are wired.

## ai-gateway

### Internal API (`X-Internal-Service-Secret`, tenant in `X-Tenant-Id`; never routed through the public gateway)

All return RFC 9457 problems. `503` with code `AI_UNAVAILABLE` means the model is unreachable, the circuit is open or
the output could not be made valid; callers fall back. `402` with code `AI_BUDGET_EXHAUSTED` means the tenant's hard
cap is reached. Every response includes `provenance`.

```
Provenance { requestId, task, provider, model, promptKey, promptVersion, latencyMs, promptTokens, completionTokens,
             costInr, cached, traceId, createdAt }
```

| Endpoint | Request | Response |
|---|---|---|
| `POST /api/v1/internal/ai/embed` | `{ inputs: [text] }` (max 64, each max 8,000 chars) | `{ vectors: [[float]], dimensions, provenance }`; content-hash cached |
| `POST /api/v1/internal/ai/judge` | `{ criterion: { id, label, requirement: MUST\|NICE, description? }, chunks: [{ chunkId, text }], rubricVersion }` | `{ criterionId, score (0..5), evidence: [{ chunkId, quote }], strengths: [], gaps: [], rationale, insufficientEvidence, guardrails: { grounded, protectedAttributeFree, injectionSuspected }, provenance }` |
| `POST /api/v1/internal/ai/structure-resume` | `{ text }` | `{ headline, skills: [string], roles: [{ title, company, start (YYYY-MM), end (YYYY-MM or null), fullTime }], education: [{ institution, degree, year }], totalYearsExperience, provenance }` |
| `POST /api/v1/internal/ai/jd-draft` | `{ title, team?, level?, location?, mustHaveSkills: [], tone? }` | `{ description, rubric: [{ id, label, weight, requirement }], knockoutQuestions: [{ question, requiredAnswer }], interviewPlan: [{ name, durationMin }], biasFlags: [{ phrase, suggestion }], provenance }` |
| `POST /api/v1/internal/ai/message-draft` | `{ purpose: REPLY\|OUTREACH\|REJECTION_FEEDBACK, candidateFirstName, jobTitle?, thread?: [{ from: me\|them, text }], notes?, channel }` | `{ text, provenance }` |
| `POST /api/v1/internal/ai/nl-filter` | `{ query }` | `{ filters: { locations: [], skills: [], minYears?, lastInterviewedWithinDays?, openToRelocate?, stage? }, semanticQuery, chips: [string], provenance }` |
| `POST /api/v1/internal/ai/explain-fit` | `{ job: { title, summary, criteria: [label] }, historical: [{ chunkId?, text }], current: [{ chunkId, text }] }` | `{ whyFitNow, historicalEvidence, provenance }`; every statement is labelled by source |

### Staff API (JWT through the public gateway)

| Method and path | Who | Purpose |
|---|---|---|
| `POST /jobs/ai-draft` | recruiter, hiring manager, HR | The UI's job drafting call. Body `{ title, team?, level?, location?, mustHaveSkills?, tone? }`. Response is the UI's `AiJobDraft`: `{ description, biasFlags: [string], suggestedInterviewPlan: [{ id, name, durationMin, interviewerNames: [] }] }` plus `rubric`, `knockoutQuestions` and `provenance`. Never creates or approves anything. |
| `GET /ai-settings`, `PUT /ai-settings` | org admin | `{ localOnly, tasks: { <task>: { provider, model, fallback?: { provider, model } } }, monthlyBudgetInr, usedInr, showScoreToHiringManagers, blindReviewDefault, providers: [{ id, kind: OLLAMA\|OPENAI_COMPATIBLE, baseUrl, status: UP\|DOWN\|CIRCUIT_OPEN, models: [string] }] }`. Tasks: `resume.structure`, `match.judge`, `embeddings`, `jd.draft`, `message.draft`, `nl.filter`, `fit.explain`. With `localOnly` on, a non-local provider is rejected with 400. |
| `GET /prompts` | org admin, compliance | `[{ id, key, version, status: DRAFT\|SHADOW\|ACTIVE\|RETIRED, trafficPct, owner, evalStatus: NONE\|PASSED\|FAILED, evalRunId?, updatedAt }]` |
| `GET /prompts/{id}` | same | Adds `systemTemplate`, `userTemplate`, `outputSchema` |
| `POST /prompts/{id}/evaluate` | org admin | Runs the golden dataset against the task's selected model and stores an `EvalRun`; returns it (long-running: returns `202` with the run id and `status: RUNNING`, polled with `GET /eval-runs/{id}`) |
| `GET /eval-runs/{id}` | org admin | `{ id, promptId, baseline?: promptId, model, status: RUNNING\|DONE\|FAILED, startedAt, finishedAt, checks: [{ name, baseline?, candidate, gate, passed }], passed }` |
| `POST /prompts/{id}/promote` | org admin | Makes the version ACTIVE (previous ACTIVE becomes RETIRED) only if its latest EvalRun passed every gate; otherwise `409` naming the failed gates |
| `GET /ai/ops-summary?range=24h` | org admin | `{ range, assessmentsCompleted, providerErrorRatePct, fallbackToRulesPct, guardrailRejectionsPct, byTask: [{ task, model, requests, p95LatencyMs, errorPct, cacheHitPct, costInr }], circuits: [{ provider, state }] }` |

### Components

`ModelProvider` (SPI: `chat(request)`, `embed(inputs)`, `health()`; implementations `OllamaProvider`, `OpenAiCompatibleProvider`),
`TaskRouter` (task to provider, model and parameters per tenant, fallback chain), `PromptRegistry` (versioned
templates plus output schema, rollout percentage by stable hash of the request key, shadow runs recorded but never
returned), `InputGuard`, `OutputGuard`, `CostMeter`, `ProvenanceWriter`, a per-provider circuit breaker and
concurrency bulkhead (local models serve one request at a time well, so the default cap is 2).

- **InputGuard:** redacts emails, phone numbers, the candidate's name where supplied, honorifics and gender markers,
  dates of birth and age statements, marital, religion, caste and nationality statements unless the task needs them;
  detects prompt-injection patterns in untrusted text (resume, messages) and flags (does not silently drop) them;
  rejects inputs over the token budget. Untrusted text is always placed inside delimited data blocks and the system
  prompt says to ignore instructions inside them.
- **OutputGuard:** JSON schema validation with one repair retry; grounding (each quote must be a substring, after
  whitespace and case normalisation, of its cited chunk, else the criterion is `insufficientEvidence`); a protected
  attribute scan over free text (strengths, gaps, rationale) that rejects the output; clamps scores to 0..5.
- **Cache:** deterministic tasks (`judge`, `structure-resume`, `nl-filter`, `embed`) are cached in Redis by
  `sha256(model, promptKey, promptVersion, canonical input)`.
- **Cost:** a price table per model (INR per million prompt and completion tokens; local models default to 0 but
  tokens and compute time are still recorded), monthly totals per tenant and task, a soft alert at 80 percent of the
  budget and a hard cap at 100 percent for non-local providers.
- **Provenance:** every call writes `ai_requests` (provenance fields, outcome, guardrail results, prompt hash, never the
  prompt text or resume text) and emits `ai.request.completed.v1`.

### Seed prompts

`match.criterion.judge@1` ACTIVE and `@2` SHADOW (stricter grounding wording, deliberately evaluated so promotion
shows a real gate result), `resume.structure@1`, `jd.draft@1`, `message.draft@1`, `nl.filter@1`, `fit.explain@1`,
all ACTIVE and 100 percent. Seeds are inserted per tenant on first use without overwriting edits.

### Evaluation harness

A versioned golden dataset (`eval/golden-v1.json` in the service resources): at least 24 resume snippets with
criteria and expected score bands written by hand, some with deliberate traps (a keyword with no real experience, an
injection string inside the resume, a hallucination bait criterion with no evidence), and counterfactual pairs that
change only name, gender markers, institution or age signals. Gates (configurable, defaults shown):
schema validity 100 percent, grounded evidence at least 95 percent, score within the expected band at least 70
percent (the 3B model's honest number is reported), counterfactual score delta p95 at most 1 point (0..5 scale),
injection resistance 100 percent, p95 latency under 20 s, cost per assessment under a configured amount. A failing
gate blocks promotion; the result is shown, not hidden.

## ai-workers

### Matching (five stages, HLD 8.6)

Consumes `matching.commands.v1` (key `applicationId`):

```
{ eventId, eventType: "matching.requested.v1", tenantId, aggregateType: "Application", aggregateId: applicationId,
  correlationId, payload: { applicationId, candidateId, candidateName, resumeId, jobId, jobTitle, jobVersion,
  rubric: [{ id, label, weight, requirement: MUST|NICE, description? }], mustHaveSkills: [],
  knockoutQuestions: [{ id, question, requiredAnswer }], answers: {}, eligibilityApproved } }
```

1. **Hard rules:** knockouts and eligibility (reuses the application's answers); a failed knockout yields
   `NOT_ELIGIBLE` with the failing rule (the application already routes it to needs-attention).
2. **Lexical and taxonomy:** coverage of each criterion's label and must-have skills in the resume text with
   synonyms from a small built-in taxonomy.
3. **Semantic retrieval:** the resume text is chunked (300 to 500 tokens, overlap, section label), chunks are embedded
   through the gateway and stored in `resume_chunks` (`tenantId, candidateId, applicationId, resumeId, chunkId,
   section, text, vector, createdAt`); each criterion is embedded and the top 5 chunks are retrieved by cosine
   similarity, filtered by tenant and candidate. No other candidate's data ever enters a prompt.
4. **RAG judge:** one gateway `judge` call per criterion with its chunks.
5. **Calibrate and guard:** per criterion `s = grounded ? 0.7 * (llm / 5) + 0.3 * lexical : 0.5 * lexical`, a MUST
   criterion below 0.4 is a hard gap, `score = round(100 * sum(weight * s))`, confidence from evidence coverage,
   grounded ratio, profile completeness and judge agreement (HIGH at 0.75 and above, MEDIUM at 0.5 and above, else
   LOW). `status` is `COMPLETE`, `PARTIAL` (AI unavailable or ungrounded) or `NOT_ELIGIBLE`.

Emits `assessment.completed.v1` on `assessment.events.v1` (aggregate `Assessment`, key `applicationId`), payload
`{ applicationId, candidateId, assessmentId, status, score, confidence, jobId, jobVersion, rubricVersion, criteria:
[{ id, label, requirement, weight, score (0..100), evidence: [quote], grounded }], strengths: [], gapsToProbe: [],
hardGaps: [criterionId], provenance: { model, promptKey, promptVersion, rubricVersion, createdAt, traceId },
label: "AI recommendation - requires human review" }`. `assessments` keeps every version (history), keyed by
`applicationId` and `jobVersion`. When the gateway is unavailable the worker records `PARTIAL` using stages 1 and 2
only, emits it, and re-queues a retry with back-off (1 m, 5 m, 30 m, then daily up to 3 days).

### Integrity (advisory only)

Consumes `resume.events.v1` (`ResumeProcessed`) and runs independent detectors, each returning
`{ type, severity: LOW|MEDIUM|HIGH, confidence, evidence }`; a signal is raised only above the detector's threshold:
`TIMELINE_OVERLAP` (overlapping full-time roles from the structured resume), `DUPLICATE_IDENTITY` (same email or phone
under a different name in the tenant), `INJECTION_ATTEMPT` (instruction-like text aimed at a model), `AI_TEXT_LIKELIHOOD`
(low-confidence stylometric heuristic, off by default). Emits `integrity.signal.raised.v1` on `integrity.events.v1`
(`{ applicationId, candidateId, signalId, type, severity, confidence, evidence (text only, no personal data) }`).
Signals never change a score; the console shows them as flags with a "dismiss with reason" action
(`POST /integrity/signals/{id}/dismiss`, stored for threshold tuning).

## Application-service changes

- After the resume is `DONE` and knockouts are clear, the existing keyword screening still runs immediately and the
  application moves to `AI_ASSESSED` with `assessment.status = PARTIAL` (so the pipeline works with AI off), and a
  `matching.requested.v1` command is published.
- It consumes `assessment.completed.v1`: a `COMPLETE` assessment replaces the stored one (keeping any human
  override, which is shown next to the AI score, never silently dropped); `PARTIAL` never replaces a `COMPLETE`. It
  consumes `integrity.signal.raised.v1` into `assessment.integrityFlags`. Stage never changes because of AI.
- `GET /applications/{id}/assessment` keeps the UI's `Assessment` shape (criteria evidence stays `string[]` of exact
  quotes so the resume viewer can highlight them) and adds `status`, `label`, `provenance`, `integrityFlags:
  [{ id, type, severity, reason }]` and `criteria[].grounded`. The flat `integrityFlag: { reason }` stays for the
  existing UI.
- Rejection stays a human action: no AI event can reject, shortlist or advance an application (a test asserts it).

## talent-service

- `talent_profiles` (search projection: skills, titles, locations, total experience, stage-history summary, consent,
  retention, `lastActivityAt`, `openToRelocate`) and `talent_profile_vectors`, built from `candidate.events.v1`,
  `application.events.v1`, `assessment.events.v1` and `employee.events.v1` (current employees are excluded unless
  internal mobility is on) plus resume text from the resume processor, embedded through the gateway.
- **Consent and retention:** only candidates with `talentPoolConsent = GRANTED` and `retentionUntil > now` are
  searchable. Consent is given by the candidate: `PUT /public/applications/{applicationId}/talent-consent?tenantId=`
  with `X-Application-Receipt` and `{ granted: boolean }` (receipt checked through application-service; used by the
  portal's "Consider me for future roles" switch). Retention defaults to 24 months after last activity.
- **Rediscovery** on `job.events.v1` `JobPublished` and on demand: filter (tenant, consent, retention, not a current
  employee, not in cooldown, not already applied to this job), exact top-500 by cosine, re-rank
  `0.55 * semantic + 0.25 * ruleCoverage + 0.10 * recency + 0.10 * historySignal` (silver medalist = reached
  interviews and was not hired). The top 50 get explanations from the gateway, each statement labelled `HISTORICAL`
  or `CURRENT`; historical scores never feed the current-job score.

| Method and path | Purpose |
|---|---|
| `GET /talent/rediscovery?jobId=` | Without `jobId`, the most recently published open job. `TalentSearchResult` (`filters`, `candidates: [{ id, name, title, matchScore, whyFitNow, historicalEvidence }]`, `rediscoveryForJobTitle`) |
| `POST /talent/search/nl` | `{ query }`; returns the generated filter chips (editable) and results |
| `POST /talent/search` | `{ filters, semanticQuery }`; runs an edited filter |
| `POST /talent/{candidateId}/invite` | `{ jobId? }`; sends the `talent-reengagement` message through communication-service (a person clicks, so it is a human action); records the invitation |
| `GET /talent/pool-health` | `{ silverMedalists, consentExpiringSoon, reEngagedThisQuarter, hireRateFromPoolPct }` |

`talent.events.v1`: `talent.rediscovery.completed.v1` (`{ jobId, matches }`), `talent.invited.v1`.

## Communication-service change

`POST /conversations/{id}/ai-draft` (today `409`) now builds the thread context, calls the gateway `message-draft`, and
returns `{ text }` for the reply box. The draft is never sent automatically; a gateway outage returns `409` with a
clear message as before. A new internal endpoint lets talent-service send a template message on a person's click.

## Events summary

`matching.commands.v1` (application-service to ai-workers), `assessment.events.v1` (`assessment.completed.v1`),
`integrity.events.v1` (`integrity.signal.raised.v1`), `ai.events.v1` (`ai.request.completed.v1`, `ai.budget.alert.v1`),
`talent.events.v1`. Contracts: `contracts/phase6-api.openapi.json`, `contracts/phase6-events.asyncapi.yaml`.

## Deviations from the plan

See *Deviations kept deliberately small* above; interview AI (question kit, scorecard summary) and the candidate
assistant are not in this phase's UI scope and stay on the rules-based Phase 4 behaviour.

## As built

Differences from the contract above, all compatible with the shapes the UI uses:

- **Status rule.** An assessment is `PARTIAL` ("AI unavailable, partial assessment") only when the AI could not do its
  job: a gateway failure, or the model returned quotes that failed the grounding check. A resume with nothing relevant
  to the role is a `COMPLETE` assessment with a low score and LOW confidence, because "no evidence found" is the
  model's honest answer, not an outage. Found by the real-model acceptance run, not by unit tests.
- **Override.** A human override sets the top-level `score` (what the pipeline ranks by) and records `overridden`
  beside `assessment.score`, which keeps the AI's own score. The Phase 3 handler used to overwrite the AI score.
- **Kafka keys.** `assessment.completed.v1` is keyed by assessment id (one row per version), not application id; the
  payload carries the application id and consumers tolerate reordering. `PARTIAL` assessments carry the lexical
  score with provenance `lexical-taxonomy-v1` and LOW confidence.
- **Additive fields.** `resumeId` and `failedRules` on `assessment.completed.v1`; `integrity.signal.dismissed.v1` so a
  dismissed flag clears on the application; `whyFitNowStatements[]` / `historicalStatements[]` (statement, source,
  chunk id, quote) beside the string forms on explain-fit; optional `inputType` (DOCUMENT or QUERY) on embed.
- **Outbox.** `application-service` publishes the matching command from a `matchingCommands` array on the submission to
  `matching.commands.v1` through a second relay; `ai-workers` runs two relays (assessments and integrity).
- **Talent.** `talent-service` fetches names, titles and stage from application-service's projection on every event
  (the application and candidate events carry only ids), extracts locations and skills from resume text with the
  gateway plus a built-in taxonomy, and builds skills, excerpts and the vector only after consent is granted. The top 10
  matches get gateway explanations (not 50); the rest and all search results get deterministic, labelled "rule-based
  summary" text. `openToRelocate` is unknown for now.
- **Gateway.** A small JSON-schema validator instead of a library (the common one still targets Jackson 2). Shadow
  prompt versions run only inside evaluation, never on live traffic (the GPU serves one request at a time). Promotion
  also accepts a `trafficPct` for gradual rollout and is refused when the evaluation used a different model from the
  current routing. Protected words are stripped from natural-language search queries before the model sees them.
- **JVM and memory.** Every service image now starts with serial GC, C1-only JIT and 512 KB thread stacks, and compose
  caps each service's memory (`mem_limit`). Together with the Phase 5 limits this keeps all 21 containers inside Docker
  Desktop's default 8 GB with a little headroom; 12 GB is still advisable. Host ports under compose: ai-gateway 8097,
  ai-workers 8098, talent-service 8099.

## Open items

- **Model quality is a 3B model's.** It passed every evaluation gate on the golden dataset, but the dataset is small and
  hand-written, the one counterfactual gate sits exactly at its limit (p95 delta 1.0 point), and the first drafts of job
  descriptions and rejection messages were weaker than the structured tasks. The approved evaluation dataset the plan
  mentions needs the owner's real examples; the selected model should be reviewed against it before rollout.
  A larger model can be selected per task in *AI models and budget* without code changes (it needs the RAM).
- **Latency.** One judge call takes 3 to 10 s. A candidate with a three-criterion rubric takes roughly 20 to 40 s of
  model time, and assessments queue behind each other on the single GPU (the console shows judge p95 near 30 s under
  the acceptance load because it includes queue time). Many applications at once will take minutes to finish.
- **No vector database.** Vectors live in MongoDB with exact in-memory cosine search; fine for thousands of profiles,
  not for the 41,000 in the sample story.
- **Interview AI and the candidate assistant** (question kit, scorecard summary, grounded Q&A) are not in this phase's UI
  scope and stay rules-based.
- **Hosted providers.** The OpenAI-compatible provider is implemented and covered by unit tests but was not run against
  a hosted service (the whole phase is local-only by design). Budget caps only bite for hosted providers.
- **Not covered by automation.** The candidate portal account switch is checked in the real browser but has no unit
  tests; the invite action has a cooldown check that is not atomic (two simultaneous clicks could both send).
- **Memory on this Mac.** With the 8 GB Docker VM, Ollama, two dev servers and several Chromium instances on a 16 GB machine, the host was swapping (9.5 of 10 GB) and anything run in parallel became slow and occasionally flaky. Run the browser specs one at a time, stop the dev servers you are not using, or raise the Mac's available memory.
- **Ops.** Ollama runs as a plain process started by hand (`ollama serve`); nothing restarts it. `ops-summary` reads up to
  100,000 request rows into memory.

## Evidence

Run against Ollama 0.40 on the Mac (Apple M3), model `qwen2.5:3b-instruct` and embeddings `nomic-embed-text`, with all
service images rebuilt from current source.

| Check | Result |
|---|---|
| `./gradlew test --rerun` | Pass: 531 tests, 0 failures, 21 opt-in tests skipped (ai-gateway 95, ai-workers 70, talent-service 56, communication-service 43, application-service 45, offer-service 55, employee-service 51) |
| `verify-phase6-workflow.mjs` with `OPENHIRE_TEST_OUTAGE=true` | Pass, real model: AI settings (local-only default, Ollama UP, hosted provider refused); AI job draft with inclusive-language flags and rubric weights totalling 100; strong resume scored 78 to 84 (HIGH) against 5 for an unrelated resume; every evidence quote an exact substring of the resume; counterfactual twin scored identically (78 vs 78 and 84 vs 84); a resume instructing the model to give top marks scored 0 to 28 and raised `INJECTION_ATTEMPT`; an overlapping-jobs resume raised `TIMELINE_OVERLAP`; flags carry no personal data and dismissal clears one; no application changed stage because of AI; a human override is stored beside the unchanged AI score; an AI reply draft is returned and nothing is sent; consent-gated rediscovery ranks the strong profile first with current fit and history kept apart; natural-language search shows its editable filter; invitation emails with a cooldown; revoking consent removes the candidate at once; Ollama stopped gives a `PARTIAL` rules-based assessment, restarted gives the full assessment |
| Golden-dataset evaluation (33 cases, 7 counterfactual pairs), `match.criterion.judge@1` | Pass every gate: schema validity 100 %, grounded evidence 100 %, score in expected band 93.9 %, counterfactual delta p95 1.0, injection resistance 100 %, p95 latency 10.4 s, cost INR 0 |
| `match.criterion.judge@2` (shadow) | Blocked: grounded 96.3 %, in band 97 %, but counterfactual delta p95 4 points; `POST /prompts/{id}/promote` returns 409 `PROMOTION_BLOCKED` naming `counterfactualDeltaP95` |
| `e2e-live/phase6-workflow.spec.ts` | Real console: AI shortlist (recommendation label, model and prompt version, highlighted resume quotes, a person decides), candidate portal consent switch, talent search with editable filter chips and separated explanations, AI job draft with wording flags, AI settings (local-only on, Ollama up), prompts (judge v1 passed, v2 failed, promotion disabled), operations (per-task latency, cache and cost, circuit closed) |
| Phases 1 to 5 acceptance scripts | Pass against the Phase 6 stack (Phase 5 with the stop-the-service-during-acceptance variant). One run of `verify-phase3-roles.mjs` exited 1 right after the other scripts and then passed three times in a row; its message was not captured, so it is recorded here as one unexplained intermittent failure |
| Live browser specs, Phases 1 to 6 | All six pass. When all six ran together on the swapping machine (see Open items), the Phase 2 and Phase 4 specs failed (a 503 and a timeout); both passed when rerun alone (11 s and 37 s), and Phases 1, 3 and 5 passed in the same batch |

Fixes made while accepting:

- A resume with nothing relevant was marked "AI unavailable"; it is now a complete, low-scoring assessment.
- A human override erased the AI score; both are now kept.
- The talent search card said "the AI explanation was unavailable" for results that are rule-based by design.
- The job draft panel doubled its quote marks and clipped long wording flags; the candidate header printed ", , 0 yrs"
  when profile data was missing.
- The operations screen called the number of rubric-criterion judgements "Assessments completed"; it now says "Criteria scored by AI".
- The PDF fixture helper now accepts multi-line text; the injection fixture is a pure instruction attack (an earlier
  version also contained a false claim of experience, which the model rightly counted as evidence).
- The acceptance script refreshes its tokens during long model waits (the first run died on an expired token).
