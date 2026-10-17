package com.openhire.aigateway.adapter.in.web;

import com.openhire.aigateway.application.AiExceptions.AccessDenied;
import com.openhire.aigateway.application.AiTaskService;
import com.openhire.aigateway.application.AiViews.DraftResponse;
import com.openhire.aigateway.application.AiViews.JdRequest;
import com.openhire.aigateway.application.EvaluationService;
import com.openhire.aigateway.application.OpsService;
import com.openhire.aigateway.application.PromptAdminService;
import com.openhire.aigateway.application.PromptAdminService.PromptDetail;
import com.openhire.aigateway.application.PromptAdminService.PromptSummary;
import com.openhire.aigateway.application.SettingsService;
import com.openhire.aigateway.application.SettingsService.SettingsView;
import com.openhire.aigateway.application.SettingsService.UpdateInput;
import com.openhire.aigateway.domain.AiTypes.RunStatus;
import com.openhire.aigateway.domain.EvalRun;
import com.openhire.aigateway.domain.EvalRun.CaseResult;
import com.openhire.aigateway.domain.EvalRun.Check;
import com.openhire.support.Access;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Staff-facing AI endpoints, authorised by realm role. */
@RestController
@RequestMapping("/api/v1")
class StaffAiController {
    private static final Set<String> DRAFTERS =
            Set.of("RECRUITER", "HIRING_MANAGER", "HR_ADMIN", "ORG_ADMIN", "SUPER_ADMIN");
    private static final Set<String> ADMINS = Set.of("ORG_ADMIN", "SUPER_ADMIN");
    private static final Set<String> PROMPT_READERS = Set.of("ORG_ADMIN", "SUPER_ADMIN", "COMPLIANCE_OFFICER");

    record PromoteInput(Integer trafficPct) {}

    record EvalRunView(
            String id,
            String promptId,
            String baseline,
            String model,
            RunStatus status,
            Instant startedAt,
            Instant finishedAt,
            List<Check> checks,
            boolean passed,
            int casesRun,
            int casesTotal,
            List<CaseResult> cases,
            String error) {
        static EvalRunView of(EvalRun r) {
            return new EvalRunView(
                    r.id(),
                    r.promptId(),
                    r.baseline(),
                    r.model(),
                    r.status(),
                    r.startedAt(),
                    r.finishedAt(),
                    r.checks(),
                    r.passed(),
                    r.casesRun(),
                    r.casesTotal(),
                    r.cases(),
                    r.error());
        }
    }

    private final Access access;
    private final AiTaskService tasks;
    private final SettingsService settings;
    private final PromptAdminService prompts;
    private final EvaluationService evaluation;
    private final OpsService ops;

    StaffAiController(
            Access access,
            AiTaskService tasks,
            SettingsService settings,
            PromptAdminService prompts,
            EvaluationService evaluation,
            OpsService ops) {
        this.access = access;
        this.tasks = tasks;
        this.settings = settings;
        this.prompts = prompts;
        this.evaluation = evaluation;
        this.ops = ops;
    }

    private String require(Authentication auth, Set<String> roles) {
        if (roles.stream().noneMatch(role -> access.role(auth, role)))
            throw new AccessDenied("This action needs one of the roles: " + String.join(", ", roles));
        return access.tenant(auth);
    }

    @PostMapping("/jobs/ai-draft")
    DraftResponse draft(Authentication auth, @RequestBody JdRequest request) {
        return tasks.staffDraft(require(auth, DRAFTERS), request);
    }

    @GetMapping("/ai-settings")
    SettingsView settings(Authentication auth) {
        return settings.view(require(auth, ADMINS));
    }

    @PutMapping("/ai-settings")
    SettingsView updateSettings(Authentication auth, @RequestBody UpdateInput input) {
        var tenant = require(auth, ADMINS);
        return settings.update(tenant, access.actor(auth), input);
    }

    @GetMapping("/prompts")
    List<PromptSummary> prompts(Authentication auth) {
        return prompts.list(require(auth, PROMPT_READERS));
    }

    @GetMapping("/prompts/{id}")
    PromptDetail prompt(Authentication auth, @PathVariable String id) {
        return prompts.get(require(auth, PROMPT_READERS), id);
    }

    @PostMapping("/prompts/{id}/evaluate")
    ResponseEntity<EvalRunView> evaluate(Authentication auth, @PathVariable String id) {
        var run = evaluation.start(require(auth, ADMINS), id, access.actor(auth));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(EvalRunView.of(run));
    }

    @GetMapping("/eval-runs/{id}")
    EvalRunView evalRun(Authentication auth, @PathVariable String id) {
        return EvalRunView.of(evaluation.get(require(auth, ADMINS), id));
    }

    @PostMapping("/prompts/{id}/promote")
    PromptSummary promote(
            Authentication auth, @PathVariable String id, @RequestBody(required = false) PromoteInput input) {
        return prompts.promote(
                require(auth, ADMINS), id, input == null ? null : input.trafficPct(), access.actor(auth));
    }

    @GetMapping("/ai/ops-summary")
    OpsService.Summary summary(Authentication auth, @RequestParam(defaultValue = "24h") String range) {
        return ops.summary(require(auth, ADMINS), range);
    }
}
