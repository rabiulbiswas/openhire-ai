package com.openhire.aigateway.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.openhire.aigateway.Fixtures.Rig;
import com.openhire.aigateway.domain.AiTypes.ProviderException;
import com.openhire.aigateway.domain.Golden;
import com.openhire.aigateway.domain.Json;
import com.openhire.aigateway.exception.AiErrors;
import com.openhire.platform.web.GlobalExceptionHandler;
import com.openhire.support.Access;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AiControllersTest {
    private static final String SECRET = "test-secret";
    private Rig rig;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        rig = new Rig();
        var access = new Access("oidc", "demo", SECRET);
        mvc = MockMvcBuilders.standaloneSetup(
                        new InternalAiController(rig.tasks, access),
                        new StaffAiController(access, rig.tasks, rig.settings, rig.admin, rig.evaluation, rig.ops))
                .setControllerAdvice(new AiErrors(), new GlobalExceptionHandler())
                .build();
    }

    private static JwtAuthenticationToken as(String... roles) {
        var jwt = new Jwt(
                "t",
                Instant.now(),
                Instant.now().plusSeconds(60),
                Map.of("alg", "none"),
                Map.of("sub", "user-1", "organization_id", "t1"));
        return new JwtAuthenticationToken(
                jwt,
                java.util.Arrays.stream(roles)
                        .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                        .toList());
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, Object body) {
        return b.contentType(MediaType.APPLICATION_JSON).content(Json.write(body));
    }

    private MockHttpServletRequestBuilder internal(String path, Object body) {
        return json(
                post("/api/v1/internal/ai/" + path)
                        .header("X-Internal-Service-Secret", SECRET)
                        .header("X-Tenant-Id", "t1"),
                body);
    }

    private static final Map<String, Object> JUDGE_BODY = Map.of(
            "criterion", Map.of("id", "k", "label", "Kafka", "requirement", "MUST"),
            "chunks", List.of(Map.of("chunkId", "c1", "text", "Built an order pipeline on Kafka at Acme.")),
            "rubricVersion", "v1");

    // ---- internal ----

    @Test
    void judgeReturnsTheContractShape() throws Exception {
        rig.local.reply = r -> Json.write(Map.of(
                "score",
                4,
                "evidence",
                List.of(Map.of("chunkId", "c1", "quote", "Built an order pipeline on Kafka")),
                "strengths",
                List.of("Kafka"),
                "gaps",
                List.of(),
                "rationale",
                "ok"));
        mvc.perform(internal("judge", JUDGE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.criterionId").value("k"))
                .andExpect(jsonPath("$.score").value(4))
                .andExpect(jsonPath("$.evidence[0].chunkId").value("c1"))
                .andExpect(jsonPath("$.insufficientEvidence").value(false))
                .andExpect(jsonPath("$.guardrails.grounded").value(true))
                .andExpect(jsonPath("$.guardrails.protectedAttributeFree").value(true))
                .andExpect(jsonPath("$.guardrails.injectionSuspected").value(false))
                .andExpect(jsonPath("$.provenance.promptKey").value("match.criterion.judge"))
                .andExpect(jsonPath("$.provenance.cached").value(false));
    }

    @Test
    void embedReturnsVectorsDimensionsAndProvenance() throws Exception {
        mvc.perform(internal("embed", Map.of("inputs", List.of("a", "b"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vectors.length()").value(2))
                .andExpect(jsonPath("$.dimensions").value(3))
                .andExpect(jsonPath("$.provenance.task").value("embeddings"));
    }

    @Test
    void internalEndpointsNeedTheSecretAndATenant() throws Exception {
        mvc.perform(json(post("/api/v1/internal/ai/embed").header("X-Tenant-Id", "t1"), Map.of("inputs", List.of("a"))))
                .andExpect(status().isUnauthorized());
        mvc.perform(json(
                        post("/api/v1/internal/ai/embed")
                                .header("X-Internal-Service-Secret", "wrong")
                                .header("X-Tenant-Id", "t1"),
                        Map.of("inputs", List.of("a"))))
                .andExpect(status().isUnauthorized());
        mvc.perform(json(
                        post("/api/v1/internal/ai/embed").header("X-Internal-Service-Secret", SECRET),
                        Map.of("inputs", List.of("a"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void providerDownIsA503ProblemWithTheStableCode() throws Exception {
        rig.local.failure = new ProviderException("down", true);
        mvc.perform(internal("judge", JUDGE_BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_UNAVAILABLE"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    void invalidJsonAfterRepairIsA503() throws Exception {
        rig.local.reply = r -> "not json";
        mvc.perform(internal("judge", JUDGE_BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_UNAVAILABLE"));
    }

    @Test
    void anExhaustedBudgetIsA402() throws Exception {
        rig.settings.update(
                "t1",
                "a",
                new com.openhire.aigateway.service.SettingsService.UpdateInput(
                        false,
                        Map.of(
                                "match.judge",
                                new com.openhire.aigateway.service.SettingsService.TaskIn("openai", "gpt-test", null)),
                        10.0,
                        null,
                        null));
        rig.usage.preload("t1", rig.meter.month(), 10);
        mvc.perform(internal("judge", JUDGE_BODY))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("AI_BUDGET_EXHAUSTED"));
    }

    @Test
    void validationErrorsAre400Problems() throws Exception {
        mvc.perform(internal("embed", Map.of("inputs", List.of())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void theRemainingInternalEndpointsRespond() throws Exception {
        rig.local.reply = r -> {
            var system = r.messages().get(0).content();
            if (system.contains("convert resume text"))
                return Json.write(Map.of(
                        "headline",
                        "Dev",
                        "skills",
                        List.of(),
                        "roles",
                        List.of(),
                        "education",
                        List.of(),
                        "totalYearsExperience",
                        1));
            if (system.contains("search request"))
                return Json.write(
                        Map.of("filters", Map.of("skills", List.of("Java")), "semanticQuery", "java developer"));
            if (system.contains("recruiter to a job candidate"))
                return Json.write(
                        Map.of(
                                "text",
                                "Hi Asha, thanks for your message. We will be in touch soon.\nBest regards,\nThe Recruiting Team"));
            if (system.contains("explain why a past candidate"))
                return Json.write(Map.of("whyFitNow", List.of(), "historicalEvidence", List.of()));
            return Json.write(Map.of(
                    "description",
                    "A backend engineer builds payment services.",
                    "rubric",
                    List.of(Map.of("label", "Java", "weight", 100, "requirement", "MUST")),
                    "knockoutQuestions",
                    List.of(),
                    "interviewPlan",
                    List.of(),
                    "biasFlags",
                    List.of()));
        };
        mvc.perform(internal("structure-resume", Map.of("text", "Dev with Java")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headline").value("Dev"));
        mvc.perform(internal("nl-filter", Map.of("query", "java developers")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chips[0]").value("Skill: Java"));
        mvc.perform(internal(
                        "message-draft", Map.of("purpose", "REPLY", "candidateFirstName", "Asha", "channel", "CHAT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").exists())
                .andExpect(jsonPath("$.provenance.task").value("message.draft"));
        mvc.perform(internal(
                        "explain-fit",
                        Map.of(
                                "job",
                                Map.of("title", "Engineer", "criteria", List.of()),
                                "historical",
                                List.of(),
                                "current",
                                List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.whyFitNow").exists());
        mvc.perform(internal("jd-draft", Map.of("title", "Backend Engineer", "mustHaveSkills", List.of("Java"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rubric[0].weight").value(100))
                .andExpect(jsonPath("$.provenance.task").value("jd.draft"));
    }

    // ---- staff ----

    @Test
    void aiDraftIsOpenToRecruitersAndHrButNotToInterviewers() throws Exception {
        rig.local.reply = r -> Json.write(Map.of(
                "description",
                "A backend engineer builds payment services.",
                "rubric",
                List.of(Map.of("label", "Java", "weight", 100, "requirement", "MUST")),
                "knockoutQuestions",
                List.of(),
                "interviewPlan",
                List.of(Map.of("name", "Technical", "durationMin", 60)),
                "biasFlags",
                List.of()));
        var body = Map.of("title", "Backend Engineer", "mustHaveSkills", List.of("Java"));
        for (var role : List.of("RECRUITER", "HIRING_MANAGER", "HR_ADMIN", "ORG_ADMIN"))
            mvc.perform(json(post("/api/v1/jobs/ai-draft").principal(as(role)), body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.description").exists())
                    .andExpect(jsonPath("$.biasFlags").isArray())
                    .andExpect(jsonPath("$.suggestedInterviewPlan[0].id").value("ai-r1"))
                    .andExpect(jsonPath("$.suggestedInterviewPlan[0].interviewerNames")
                            .isArray())
                    .andExpect(jsonPath("$.rubric[0].label").value("Java"))
                    .andExpect(jsonPath("$.provenance.task").value("jd.draft"));
        mvc.perform(json(post("/api/v1/jobs/ai-draft").principal(as("INTERVIEWER")), body))
                .andExpect(status().isForbidden());
    }

    @Test
    void settingsAreForOrgAdminsOnly() throws Exception {
        mvc.perform(get("/api/v1/ai-settings").principal(as("RECRUITER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/ai-settings").principal(as("ORG_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.localOnly").value(true))
                .andExpect(jsonPath("$.tasks['match.judge'].provider").value("ollama"))
                .andExpect(jsonPath("$.usedInr").value(0.0))
                .andExpect(jsonPath("$.providers[0].status").value("UP"))
                .andExpect(jsonPath("$.providers[0].models[0]").value("qwen2.5:3b-instruct"));
    }

    @Test
    void settingsValidationErrorsNameTheField() throws Exception {
        mvc.perform(json(
                        put("/api/v1/ai-settings").principal(as("SUPER_ADMIN")),
                        Map.of("tasks", Map.of("match.judge", Map.of("provider", "openai", "model", "gpt-4o-mini")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Local-only")))
                .andExpect(jsonPath("$.errors[0].field").value("tasks.match.judge.provider"))
                .andExpect(jsonPath("$.errors[0].message").exists());
        mvc.perform(json(
                        put("/api/v1/ai-settings").principal(as("ORG_ADMIN")),
                        Map.of("monthlyBudgetInr", 2500, "blindReviewDefault", true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlyBudgetInr").value(2500.0))
                .andExpect(jsonPath("$.blindReviewDefault").value(true));
    }

    @Test
    void promptsAreReadableByComplianceButChangeableOnlyByAdmins() throws Exception {
        mvc.perform(get("/api/v1/prompts").principal(as("COMPLIANCE_OFFICER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7))
                .andExpect(
                        jsonPath("$[?(@.id=='match.criterion.judge@2')].status").value("SHADOW"));
        mvc.perform(get("/api/v1/prompts/match.criterion.judge@1").principal(as("ORG_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.systemTemplate").exists())
                .andExpect(jsonPath("$.userTemplate").exists())
                .andExpect(jsonPath("$.outputSchema.type").value("object"));
        mvc.perform(get("/api/v1/prompts/nothing@9").principal(as("ORG_ADMIN"))).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/prompts/match.criterion.judge@2/evaluate").principal(as("COMPLIANCE_OFFICER")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/prompts/match.criterion.judge@2/promote").principal(as("RECRUITER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void evaluateReturns202AndPromotionOfAFailingVersionIs409NamingGates() throws Exception {
        rig.dataset = new Golden.Dataset(
                "t",
                List.of(new Golden.Case(
                        "a",
                        null,
                        null,
                        null,
                        new Golden.Criterion("c", "skill", "MUST", null),
                        List.of(new com.openhire.aigateway.domain.OutputGuard.Chunk(
                                "c1", "Built Kafka pipelines. Reduced lag.")),
                        3,
                        5)));
        rig.local.reply = r -> Json.write(Map.of(
                "score",
                5,
                "evidence",
                List.of(Map.of("chunkId", "c1", "quote", "invented")),
                "strengths",
                List.of(),
                "gaps",
                List.of(),
                "rationale",
                "x"));
        var result = mvc.perform(
                        post("/api/v1/prompts/match.criterion.judge@2/evaluate").principal(as("ORG_ADMIN")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.promptId").value("match.criterion.judge@2"))
                .andReturn();
        String id = Json.readMap(result.getResponse().getContentAsString())
                .get("id")
                .toString();
        mvc.perform(get("/api/v1/eval-runs/" + id).principal(as("ORG_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.passed").value(false))
                .andExpect(jsonPath("$.checks[0].name").value("schemaValidityPct"))
                .andExpect(jsonPath("$.checks[0].gate").value("at least 100%"))
                .andExpect(jsonPath("$.checks[0].candidate").value(100.0))
                .andExpect(jsonPath("$.checks[0].passed").value(true));
        mvc.perform(post("/api/v1/prompts/match.criterion.judge@2/promote").principal(as("ORG_ADMIN")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROMOTION_BLOCKED"))
                .andExpect(jsonPath("$.failedGates").isArray())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("scoreInBandPct")));
        mvc.perform(get("/api/v1/eval-runs/unknown").principal(as("ORG_ADMIN"))).andExpect(status().isNotFound());
    }

    @Test
    void opsSummaryIsForAdmins() throws Exception {
        mvc.perform(get("/api/v1/ai/ops-summary?range=24h").principal(as("RECRUITER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/ai/ops-summary?range=24h").principal(as("ORG_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range").value("24h"))
                .andExpect(jsonPath("$.byTask").isArray())
                .andExpect(jsonPath("$.circuits[0].provider").value("ollama"));
    }
}
