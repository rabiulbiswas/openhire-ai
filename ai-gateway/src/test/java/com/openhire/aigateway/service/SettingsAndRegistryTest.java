package com.openhire.aigateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openhire.aigateway.Fixtures.Rig;
import com.openhire.aigateway.domain.AiTask;
import com.openhire.aigateway.domain.AiTypes.ModelRef;
import com.openhire.aigateway.domain.AiTypes.PromptStatus;
import com.openhire.aigateway.domain.AiTypes.ProviderException;
import com.openhire.aigateway.domain.AiTypes.ProviderStatus;
import com.openhire.aigateway.domain.Json;
import com.openhire.aigateway.dto.AiViews;
import com.openhire.aigateway.exception.AiExceptions.AiUnavailable;
import com.openhire.aigateway.exception.AiExceptions.ValidationFailed;
import com.openhire.aigateway.service.SettingsService.TaskIn;
import com.openhire.aigateway.service.SettingsService.UpdateInput;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SettingsAndRegistryTest {
    private static final String T = "t1";
    private Rig rig;

    @BeforeEach
    void setUp() {
        rig = new Rig();
    }

    // ---- router and settings ----

    @Test
    void defaultsComeFromConfigurationForEveryTask() {
        var view = rig.settings.view(T);
        assertThat(view.localOnly()).isTrue();
        assertThat(view.monthlyBudgetInr()).isEqualTo(1000);
        assertThat(view.tasks())
                .containsOnlyKeys(
                        "resume.structure",
                        "match.judge",
                        "embeddings",
                        "jd.draft",
                        "message.draft",
                        "nl.filter",
                        "fit.explain");
        assertThat(view.tasks().get("match.judge").model()).isEqualTo("qwen2.5:3b-instruct");
        assertThat(view.tasks().get("embeddings").model()).isEqualTo("nomic-embed-text");
        assertThat(view.providers()).extracting(p -> p.id()).containsExactly("ollama", "openai");
    }

    @Test
    void localOnlyRejectsANonLocalProviderNamingTheTask() {
        var input = new UpdateInput(
                null, Map.of("match.judge", new TaskIn("openai", "gpt-4o-mini", null)), null, null, null);
        assertThatThrownBy(() -> rig.settings.update(T, "admin", input))
                .isInstanceOfSatisfying(ValidationFailed.class, e -> {
                    assertThat(e.getMessage()).contains("Local-only").contains("match.judge");
                    assertThat(e.errors()).singleElement().satisfies(f -> assertThat(f.field())
                            .isEqualTo("tasks.match.judge.provider"));
                });
        assertThat(rig.settingsStore.rows).isEmpty();
    }

    @Test
    void aHostedProviderIsAcceptedOnceLocalOnlyIsOffAndTurningItBackOnIsRefused() {
        rig.settings.update(
                T,
                "admin",
                new UpdateInput(
                        false,
                        Map.of(
                                "match.judge",
                                new TaskIn("openai", "gpt-4o-mini", new ModelRef("ollama", "qwen2.5:3b-instruct"))),
                        500.0,
                        false,
                        true));
        var view = rig.settings.view(T);
        assertThat(view.localOnly()).isFalse();
        assertThat(view.tasks().get("match.judge").provider()).isEqualTo("openai");
        assertThat(view.tasks().get("match.judge").fallback().provider()).isEqualTo("ollama");
        assertThat(view.monthlyBudgetInr()).isEqualTo(500);
        assertThat(view.showScoreToHiringManagers()).isFalse();
        assertThat(view.blindReviewDefault()).isTrue();
        assertThat(rig.settingsStore.rows.get(T).updatedBy()).isEqualTo("admin");

        assertThatThrownBy(() -> rig.settings.update(T, "admin", new UpdateInput(true, null, null, null, null)))
                .isInstanceOfSatisfying(ValidationFailed.class, e -> assertThat(e.errors())
                        .extracting(f -> f.field())
                        .containsExactly("tasks.match.judge.provider"));
    }

    @Test
    void invalidSettingsAreRejectedWithReadableMessages() {
        assertThatThrownBy(() -> rig.settings.update(
                        T,
                        "a",
                        new UpdateInput(null, Map.of("nope", new TaskIn("ollama", "m", null)), null, null, null)))
                .isInstanceOf(ValidationFailed.class)
                .hasMessageContaining("Unknown task nope");
        assertThatThrownBy(() -> rig.settings.update(
                        T,
                        "a",
                        new UpdateInput(null, Map.of("jd.draft", new TaskIn("ghost", "m", null)), null, null, null)))
                .isInstanceOfSatisfying(
                        ValidationFailed.class,
                        e -> assertThat(e.errors().get(0).field()).isEqualTo("tasks.jd.draft.provider"));
        assertThatThrownBy(() -> rig.settings.update(
                        T,
                        "a",
                        new UpdateInput(null, Map.of("jd.draft", new TaskIn("ollama", " ", null)), null, null, null)))
                .isInstanceOf(ValidationFailed.class);
        assertThatThrownBy(() -> rig.settings.update(T, "a", new UpdateInput(null, null, -1.0, null, null)))
                .isInstanceOfSatisfying(
                        ValidationFailed.class,
                        e -> assertThat(e.errors().get(0).field()).isEqualTo("monthlyBudgetInr"));
    }

    @Test
    void providerStatusReflectsHealthAndTheCircuit() {
        rig.local.up = false;
        assertThat(rig.settings.view(T).providers().get(0).status()).isEqualTo(ProviderStatus.DOWN);
        rig.local.up = true;
        assertThat(rig.settings.view(T).providers().get(0).status()).isEqualTo(ProviderStatus.UP);
        var breaker = rig.guards.of("ollama").breaker();
        breaker.onFailure();
        breaker.onFailure();
        assertThat(rig.settings.view(T).providers().get(0).status()).isEqualTo(ProviderStatus.CIRCUIT_OPEN);
    }

    @Test
    void routesFollowTheFallbackChainWhenThePrimaryFails() {
        rig.settings.update(
                T,
                "a",
                new UpdateInput(
                        false,
                        Map.of(
                                "match.judge",
                                new TaskIn("openai", "gpt-test", new ModelRef("ollama", "qwen2.5:3b-instruct"))),
                        null,
                        null,
                        null));
        assertThat(rig.router.routes(T, AiTask.MATCH_JUDGE))
                .extracting(r -> r.provider().id())
                .containsExactly("openai", "ollama");
        rig.hosted.failure = new ProviderException("HTTP 500", true);
        rig.local.reply = r -> Json.write(Map.of(
                "score", 0, "evidence", List.of(), "strengths", List.of(), "gaps", List.of(), "rationale", "none"));
        var out = rig.tasks.judge(
                T,
                new AiViews.JudgeRequest(
                        new AiViews.CriterionIn("c", "Kafka", "MUST", null),
                        List.of(new AiViews.ChunkIn("c1", "Java developer")),
                        "v",
                        null));
        assertThat(out.provenance().provider()).isEqualTo("ollama");
        assertThat(rig.hosted.calls).hasSize(1);
    }

    @Test
    void noPermittedRouteIsUnavailable() {
        var tasks = new HashMap<>(rig.router.effective(T).tasks());
        tasks.put(
                AiTask.JD_DRAFT.name(), new com.openhire.aigateway.domain.AiSettings.TaskRoute("openai", "gpt", null));
        rig.settingsStore.save(
                new com.openhire.aigateway.domain.AiSettings(T, true, tasks, 1, true, false, null, null));
        assertThatThrownBy(() -> rig.router.routes(T, AiTask.JD_DRAFT)).isInstanceOf(AiUnavailable.class);
    }

    // ---- prompt registry ----

    @Test
    void seedsAllSevenPromptsPerTenantWithoutOverwritingEdits() {
        var list = rig.admin.list(T);
        assertThat(list)
                .extracting(p -> p.id())
                .containsExactlyInAnyOrder(
                        "match.criterion.judge@1",
                        "match.criterion.judge@2",
                        "resume.structure@1",
                        "jd.draft@4",
                        "message.draft@1",
                        "nl.filter@1",
                        "fit.explain@1");
        assertThat(rig.admin.get(T, "match.criterion.judge@2").status()).isEqualTo(PromptStatus.SHADOW);
        assertThat(rig.admin.get(T, "match.criterion.judge@1").status()).isEqualTo(PromptStatus.ACTIVE);
        assertThat(list).filteredOn(p -> p.status() == PromptStatus.ACTIVE).allMatch(p -> p.trafficPct() == 100);
        assertThat(rig.admin.get(T, "resume.structure@1").outputSchema()).containsKey("properties");

        var edited = rig.prompts.rows.get("t1:jd.draft@4");
        rig.prompts.save(new com.openhire.aigateway.domain.PromptVersion(
                edited.docId(),
                T,
                edited.key(),
                4,
                PromptStatus.ACTIVE,
                100,
                "alice",
                "EDITED {{title}}",
                edited.userTemplate(),
                edited.outputSchema(),
                edited.evalStatus(),
                null,
                edited.updatedAt()));
        new PromptRegistry(rig.prompts, rig.clock).ensureSeeded(T);
        assertThat(rig.admin.get(T, "jd.draft@4").systemTemplate()).isEqualTo("EDITED {{title}}");
        assertThat(rig.admin.list("other")).hasSize(7);
        assertThat(rig.prompts.list("other")).extracting(p -> p.tenantId()).containsOnly("other");
    }

    @Test
    void everySeedTemplateRendersWithItsFieldsAndHasAValidSchema() {
        var vars = new HashMap<String, String>();
        for (var key : List.of(
                "label",
                "requirement",
                "details",
                "chunks",
                "text",
                "title",
                "team",
                "level",
                "location",
                "skills",
                "qualification",
                "goodToHaveSkills",
                "workMode",
                "tone",
                "purpose",
                "channel",
                "firstName",
                "jobTitle",
                "thread",
                "notes",
                "query",
                "summary",
                "criteria",
                "current",
                "historical")) vars.put(key, "v-" + key);
        rig.registry.list(T).forEach(p -> {
            assertThat(p.renderSystem(vars)).isNotBlank();
            assertThat(p.renderUser(vars)).doesNotContain("{{");
            assertThat(p.schema()).containsEntry("type", "object");
        });
        assertThatThrownBy(() -> rig.registry.get(T, "jd.draft@4").renderUser(Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rolloutSplitsTrafficByAStableHash() {
        rig.registry.ensureSeeded(T);
        rig.registry.save(rig.registry
                .get(T, "match.criterion.judge@2")
                .withStatus(PromptStatus.ACTIVE, 30, rig.clock.instant()));
        rig.registry.save(rig.registry
                .get(T, "match.criterion.judge@1")
                .withStatus(PromptStatus.ACTIVE, 70, rig.clock.instant()));
        int v2 = 0;
        for (int i = 0; i < 2000; i++) {
            var first = rig.registry.select(T, "match.criterion.judge", "k" + i).version();
            assertThat(rig.registry.select(T, "match.criterion.judge", "k" + i).version())
                    .as("stable")
                    .isEqualTo(first);
            if (first == 2) v2++;
        }
        assertThat(v2).isBetween(500, 700);
    }

    @Test
    void promotingWithAPartialShareKeepsTheOlderVersionActive() {
        rig.local.reply = r -> "{}";
        rig.registry.ensureSeeded(T);
        var v2 = rig.registry.get(T, "match.criterion.judge@2");
        rig.registry.save(
                v2.withEval(com.openhire.aigateway.domain.AiTypes.EvalStatus.PASSED, "run1", rig.clock.instant()));
        rig.evals.save(new com.openhire.aigateway.domain.EvalRun(
                "t1:run1",
                T,
                "match.criterion.judge@2",
                null,
                "qwen2.5:3b-instruct",
                com.openhire.aigateway.domain.AiTypes.RunStatus.DONE,
                rig.clock.instant(),
                rig.clock.instant(),
                List.of(new com.openhire.aigateway.domain.EvalRun.Check("x", null, 1, "g", true)),
                true,
                1,
                1,
                List.of(),
                null));
        var promoted = rig.admin.promote(T, "match.criterion.judge@2", 20, "admin");
        assertThat(promoted.trafficPct()).isEqualTo(20);
        assertThat(rig.registry.get(T, "match.criterion.judge@1").status()).isEqualTo(PromptStatus.ACTIVE);
        assertThat(rig.registry.get(T, "match.criterion.judge@1").trafficPct()).isEqualTo(80);
    }

    // ---- ops ----

    @Test
    void opsSummaryAggregatesLiveTraffic() {
        rig.local.reply = r -> Json.write(Map.of(
                "score", 0, "evidence", List.of(), "strengths", List.of(), "gaps", List.of(), "rationale", "none"));
        var request = new AiViews.JudgeRequest(
                new AiViews.CriterionIn("c", "Kafka", "MUST", null),
                List.of(new AiViews.ChunkIn("c1", "Java developer")),
                "v",
                null);
        rig.tasks.judge(T, request);
        rig.tasks.judge(T, request);
        rig.local.failure = new ProviderException("down", true);
        assertThatThrownBy(() -> rig.tasks.judge(
                        T,
                        new AiViews.JudgeRequest(
                                new AiViews.CriterionIn("c2", "Spring", "MUST", null),
                                List.of(new AiViews.ChunkIn("c1", "Other text")),
                                "v",
                                null)))
                .isInstanceOf(AiUnavailable.class);
        var summary = rig.ops.summary(T, "24h");
        assertThat(summary.range()).isEqualTo("24h");
        assertThat(summary.assessmentsCompleted()).isEqualTo(2);
        assertThat(summary.providerErrorRatePct()).isEqualTo(33.3);
        assertThat(summary.fallbackToRulesPct()).isEqualTo(33.3);
        assertThat(summary.byTask()).singleElement().satisfies(line -> {
            assertThat(line.task()).isEqualTo("match.judge");
            assertThat(line.requests()).isEqualTo(3);
            assertThat(line.cacheHitPct()).isEqualTo(33.3);
        });
        assertThat(summary.circuits())
                .extracting(c -> c.provider() + ":" + c.state())
                .containsExactly("ollama:CLOSED", "openai:CLOSED");
        assertThatThrownBy(() -> rig.ops.summary(T, "yesterday")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSettingsChangeIsAuditedWithValuesAndNoFreeText() {
        rig.settings.update(T, "admin-1", new UpdateInput(null, null, 750.0, false, true));

        assertThat(rig.audit.payloads()).singleElement().satisfies(payload -> {
            assertThat(payload).containsEntry("action", "AI_SETTINGS_CHANGED");
            assertThat(payload.get("actor")).isEqualTo(Map.of("type", "USER", "id", "admin-1"));
            assertThat(payload.get("resource")).isEqualTo(Map.of("type", "AiSettings", "id", T));
            assertThat(payload.get("details"))
                    .isEqualTo(Map.of(
                            "localOnly",
                            true,
                            "monthlyBudgetInr",
                            750.0,
                            "showScoreToHiringManagers",
                            false,
                            "blindReviewDefault",
                            true,
                            "tasksChanged",
                            0));
        });
    }

    @Test
    void aRejectedSettingsChangeIsNotAuditedAndABrokenAuditStoreDoesNotBlockASaved() {
        assertThatThrownBy(() -> rig.settings.update(T, "a", new UpdateInput(null, null, -1.0, null, null)))
                .isInstanceOf(ValidationFailed.class);
        assertThat(rig.audit.payloads()).isEmpty();

        var broken = new com.openhire.aigateway.Fixtures.Rig(com.openhire.aigateway.AuditProbe.broken());
        broken.settings.update(T, "a", new UpdateInput(null, null, 10.0, null, null));
        assertThat(broken.settingsStore.rows).hasSize(1);
    }
}
