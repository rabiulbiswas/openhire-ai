package com.openhire.aigateway.service;

import com.openhire.aigateway.domain.AiTask;
import com.openhire.aigateway.domain.AiTypes.Provenance;
import com.openhire.aigateway.domain.InputGuard;
import com.openhire.aigateway.domain.OutputGuard;
import com.openhire.aigateway.domain.OutputGuard.Chunk;
import com.openhire.aigateway.domain.OutputGuard.JudgeOutput;
import com.openhire.aigateway.domain.OutputGuard.Statement;
import com.openhire.aigateway.domain.PromptVersion;
import com.openhire.aigateway.domain.ProtectedAttributes;
import com.openhire.aigateway.dto.AiViews.ChunkIn;
import com.openhire.aigateway.dto.AiViews.DraftResponse;
import com.openhire.aigateway.dto.AiViews.EmbedRequest;
import com.openhire.aigateway.dto.AiViews.EmbedResponse;
import com.openhire.aigateway.dto.AiViews.EvidenceView;
import com.openhire.aigateway.dto.AiViews.ExplainRequest;
import com.openhire.aigateway.dto.AiViews.ExplainResponse;
import com.openhire.aigateway.dto.AiViews.GuardrailsView;
import com.openhire.aigateway.dto.AiViews.JdRequest;
import com.openhire.aigateway.dto.AiViews.JudgeRequest;
import com.openhire.aigateway.dto.AiViews.JudgeResponse;
import com.openhire.aigateway.dto.AiViews.MessageRequest;
import com.openhire.aigateway.dto.AiViews.MessageResponse;
import com.openhire.aigateway.dto.AiViews.NlRequest;
import com.openhire.aigateway.dto.AiViews.StatementView;
import com.openhire.aigateway.dto.AiViews.StructureRequest;
import com.openhire.aigateway.service.ModelGateway.Call;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** The seven AI tasks: validate and prepare the input, call the gateway, shape the answer. */
public class AiTaskService {
    static final int MAX_EMBED_INPUTS = 64;
    static final int MAX_EMBED_CHARS = 8_000;
    static final int MAX_CHUNKS = 12;
    static final int MAX_CHUNK_CHARS = 4_000;
    static final int MAX_RESUME_CHARS = 24_000;

    private final ModelGateway gateway;
    private final Clock clock;

    public AiTaskService(ModelGateway gateway, Clock clock) {
        this.gateway = gateway;
        this.clock = clock;
    }

    // ---- embed ----

    public EmbedResponse embed(String tenantId, EmbedRequest request) {
        var inputs = request == null || request.inputs() == null ? List.<String>of() : request.inputs();
        if (inputs.isEmpty() || inputs.size() > MAX_EMBED_INPUTS)
            throw new IllegalArgumentException("inputs must contain 1 to " + MAX_EMBED_INPUTS + " texts");
        for (var text : inputs)
            if (text == null || text.isBlank() || text.length() > MAX_EMBED_CHARS)
                throw new IllegalArgumentException("each input must be 1 to " + MAX_EMBED_CHARS + " characters");
        var result = gateway.embed(tenantId, inputs, request.inputType());
        return new EmbedResponse(result.vectors(), result.dimensions(), result.provenance());
    }

    // ---- judge ----

    /** A judge result with the raw numbers the evaluation harness needs. */
    public record JudgeRun(JudgeResponse response, JudgeOutput output, int attempts, boolean injectionSuspected) {}

    public JudgeResponse judge(String tenantId, JudgeRequest request) {
        return judgeWith(tenantId, request, null, false).response();
    }

    public JudgeRun judgeWith(String tenantId, JudgeRequest request, PromptVersion forced, boolean eval) {
        if (request == null || request.criterion() == null) throw new IllegalArgumentException("criterion is required");
        var criterion = request.criterion();
        if (blank(criterion.id()) || blank(criterion.label()))
            throw new IllegalArgumentException("criterion.id and criterion.label are required");
        var requirement = "NICE".equalsIgnoreCase(criterion.requirement()) ? "NICE" : "MUST";
        var chunks = checkedChunks(request.chunks(), "chunks", true);
        var originals =
                chunks.stream().map(c -> new Chunk(c.chunkId(), c.text())).toList();
        boolean injection = originals.stream().anyMatch(c -> InputGuard.suspicious(c.text()));
        int[] redactions = {0};
        var shown = new StringBuilder();
        for (var chunk : chunks) {
            var redacted = InputGuard.redact(chunk.text(), request.candidateName());
            redactions[0] += redacted.count();
            if (!shown.isEmpty()) shown.append("\n\n");
            shown.append("[chunkId: ")
                    .append(oneLine(chunk.chunkId(), 64))
                    .append("]\n")
                    .append(InputGuard.neutralizeDelimiters(redacted.text()));
        }
        var vars = new LinkedHashMap<String, String>();
        vars.put("label", oneLine(criterion.label(), 160));
        vars.put("requirement", requirement);
        vars.put("details", blank(criterion.description()) ? "(none)" : oneLine(criterion.description(), 500));
        vars.put("chunks", shown.toString());
        var key = ModelGateway.keyOf(
                criterion.id(), chunks.stream().map(ChunkIn::chunkId).collect(Collectors.joining(",")));
        var outcome = gateway.chat(new Call<JudgeOutput>(
                tenantId,
                AiTask.MATCH_JUDGE,
                forced,
                key,
                vars,
                700,
                eval,
                json -> OutputGuard.judge(json, originals),
                out -> {
                    var flags = new LinkedHashMap<String, Object>();
                    flags.put("grounded", out.grounded());
                    flags.put("protectedAttributeFree", true);
                    flags.put("injectionSuspected", injection);
                    flags.put("redactions", redactions[0]);
                    flags.put("quotesTotal", out.quotesTotal());
                    flags.put("quotesGrounded", out.quotesGrounded());
                    return flags;
                }));
        var out = outcome.value();
        var response = new JudgeResponse(
                criterion.id(),
                out.score(),
                out.evidence().stream()
                        .map(e -> new EvidenceView(e.chunkId(), e.quote()))
                        .toList(),
                out.strengths(),
                out.gaps(),
                out.rationale(),
                out.insufficientEvidence(),
                new GuardrailsView(out.grounded(), true, injection),
                outcome.provenance());
        return new JudgeRun(response, out, outcome.attempts(), injection);
    }

    // ---- structure resume ----

    public Map<String, Object> structureResume(String tenantId, StructureRequest request) {
        if (request == null || blank(request.text()) || request.text().length() > MAX_RESUME_CHARS)
            throw new IllegalArgumentException("text is required (max " + MAX_RESUME_CHARS + " characters)");
        var redacted = InputGuard.redact(request.text(), request.candidateName());
        boolean injection = InputGuard.suspicious(request.text());
        var today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        var outcome = gateway.chat(new Call<Map<String, Object>>(
                tenantId,
                AiTask.RESUME_STRUCTURE,
                null,
                ModelGateway.keyOf(redacted.text()),
                Map.of("text", InputGuard.neutralizeDelimiters(redacted.text())),
                1600,
                false,
                json -> OutputGuard.structure(json, request.text(), today),
                out -> {
                    var flags = new LinkedHashMap<String, Object>();
                    flags.put("injectionSuspected", injection);
                    flags.put("redactions", redacted.count());
                    return flags;
                }));
        var result = new LinkedHashMap<>(outcome.value());
        result.put("provenance", outcome.provenance());
        return result;
    }

    // ---- job description draft ----

    public Map<String, Object> jdDraft(String tenantId, JdRequest request) {
        if (request == null || blank(request.title()) || request.title().length() > 120)
            throw new IllegalArgumentException("title is required (max 120 characters)");
        var skills = new ArrayList<String>();
        if (request.mustHaveSkills() != null)
            for (var skill : request.mustHaveSkills()) {
                if (blank(skill) || skill.length() > 60)
                    throw new IllegalArgumentException("Each required skill must contain 1 to 60 characters");
                if (skills.size() >= 15) throw new IllegalArgumentException("mustHaveSkills allows at most 15 entries");
                skills.add(oneLine(skill, 60));
            }
        var vars = new LinkedHashMap<String, String>();
        vars.put("title", oneLine(request.title(), 120));
        vars.put("team", orNone(request.team()));
        vars.put("level", orNone(request.level()));
        vars.put("location", orNone(request.location()));
        vars.put("skills", skills.isEmpty() ? "(none given)" : String.join(", ", skills));
        if (request.qualification() != null
                && (request.qualification().isBlank() || request.qualification().length() > 1000))
            throw new IllegalArgumentException("Qualification must contain 1 to 1,000 characters");
        var optionalSkills = new ArrayList<String>();
        if (request.goodToHaveSkills() != null) {
            if (request.goodToHaveSkills().size() > 15)
                throw new IllegalArgumentException("goodToHaveSkills allows at most 15 entries");
            for (var skill : request.goodToHaveSkills()) {
                if (blank(skill) || skill.length() > 60)
                    throw new IllegalArgumentException("Each optional skill must contain 1 to 60 characters");
                var cleaned = oneLine(skill, 60);
                if (skills.stream().anyMatch(required -> required.equalsIgnoreCase(cleaned)))
                    throw new IllegalArgumentException("Must-have and good-to-have skills must be separate");
                if (optionalSkills.stream().noneMatch(existing -> existing.equalsIgnoreCase(cleaned)))
                    optionalSkills.add(cleaned);
            }
        }
        vars.put(
                "qualification",
                blank(request.qualification()) ? "(not specified)" : oneLine(request.qualification(), 1000));
        vars.put("goodToHaveSkills", optionalSkills.isEmpty() ? "(none given)" : String.join(", ", optionalSkills));
        if (request.workMode() != null && !List.of("REMOTE", "ONSITE", "HYBRID").contains(request.workMode()))
            throw new IllegalArgumentException("Invalid work mode");
        vars.put("workMode", orNone(request.workMode()));
        vars.put("tone", blank(request.tone()) ? "professional and friendly" : oneLine(request.tone(), 60));
        vars.replaceAll((k, v) -> InputGuard.neutralizeDelimiters(v));
        var title = request.title().strip();
        var outcome = gateway.chat(new Call<Map<String, Object>>(
                tenantId,
                AiTask.JD_DRAFT,
                null,
                ModelGateway.keyOf("structured-jd-v4", vars),
                vars,
                1400,
                false,
                json -> {
                    var prepared = new LinkedHashMap<String, Object>(json);
                    prepared.put(
                            "description",
                            JobDraftDescription.render(json.get("description"), request, skills, optionalSkills));
                    return OutputGuard.jobDraft(prepared, title, skills, optionalSkills);
                },
                out -> Map.of("biasFlags", ((List<?>) out.get("biasFlags")).size())));
        var result = new LinkedHashMap<>(outcome.value());
        result.put("provenance", outcome.provenance());
        return result;
    }

    /** The console's AiJobDraft: description, bias flags as text, interview plan with ids. Nothing is created. */
    @SuppressWarnings("unchecked")
    public DraftResponse staffDraft(String tenantId, JdRequest request) {
        var draft = jdDraft(tenantId, request);
        var flags = (List<Map<String, Object>>) draft.get("biasFlags");
        var plan = new ArrayList<Map<String, Object>>();
        int n = 1;
        for (var round : (List<Map<String, Object>>) draft.get("interviewPlan")) {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", "ai-r" + n++);
            row.put("name", round.get("name"));
            row.put("durationMin", round.get("durationMin"));
            row.put("interviewerNames", List.of());
            plan.add(row);
        }
        return new DraftResponse(
                (String) draft.get("description"),
                flags.stream()
                        .map(f -> "\"" + f.get("phrase") + "\": " + f.get("suggestion"))
                        .toList(),
                flags,
                plan,
                (List<Map<String, Object>>) draft.get("rubric"),
                (List<Map<String, Object>>) draft.get("knockoutQuestions"),
                (Provenance) draft.get("provenance"));
    }

    // ---- message draft ----

    private static final Set<String> PURPOSES = Set.of("REPLY", "OUTREACH", "REJECTION_FEEDBACK");

    public MessageResponse messageDraft(String tenantId, MessageRequest request) {
        if (request == null) throw new IllegalArgumentException("A request body is required");
        var purpose = request.purpose() == null ? "" : request.purpose().strip().toUpperCase(Locale.ROOT);
        if (!PURPOSES.contains(purpose))
            throw new IllegalArgumentException("purpose must be REPLY, OUTREACH or REJECTION_FEEDBACK");
        if (blank(request.candidateFirstName()) || request.candidateFirstName().length() > 60)
            throw new IllegalArgumentException("candidateFirstName is required (max 60 characters)");
        var thread = new StringBuilder();
        boolean injection = false;
        if (request.thread() != null) {
            if (request.thread().size() > 20) throw new IllegalArgumentException("thread allows at most 20 messages");
            for (var item : request.thread()) {
                if (item == null || blank(item.text())) continue;
                if (item.text().length() > 2000) throw new IllegalArgumentException("a thread message is too long");
                injection |= InputGuard.suspicious(item.text());
                if (!thread.isEmpty()) thread.append('\n');
                thread.append("them".equalsIgnoreCase(item.from()) ? "Candidate: " : "Recruiter: ")
                        .append(InputGuard.neutralizeDelimiters(
                                InputGuard.redact(item.text(), null).text()));
            }
        }
        var notes = request.notes() == null ? "" : request.notes();
        if (notes.length() > 2000) throw new IllegalArgumentException("notes is too long (max 2000 characters)");
        injection |= InputGuard.suspicious(notes);
        var vars = new LinkedHashMap<String, String>();
        vars.put("purpose", purpose);
        vars.put(
                "channel",
                blank(request.channel())
                        ? "CHAT"
                        : oneLine(request.channel(), 20).toUpperCase(Locale.ROOT));
        vars.put("firstName", InputGuard.neutralizeDelimiters(oneLine(request.candidateFirstName(), 60)));
        vars.put("jobTitle", InputGuard.neutralizeDelimiters(orNone(request.jobTitle())));
        vars.put("thread", thread.isEmpty() ? "(no earlier messages)" : thread.toString());
        vars.put(
                "notes",
                blank(notes)
                        ? "(none)"
                        : InputGuard.neutralizeDelimiters(
                                InputGuard.redact(notes, null).text()));
        boolean flagged = injection;
        var outcome = gateway.chat(new Call<String>(
                tenantId,
                AiTask.MESSAGE_DRAFT,
                null,
                ModelGateway.keyOf(purpose, request.candidateFirstName(), thread),
                vars,
                500,
                false,
                json -> OutputGuard.message(json, purpose, request.candidateFirstName()),
                text -> Map.of("injectionSuspected", flagged)));
        return new MessageResponse(outcome.value(), outcome.provenance());
    }

    // ---- natural-language filter ----

    public Map<String, Object> nlFilter(String tenantId, NlRequest request) {
        if (request == null || blank(request.query()) || request.query().length() > 500)
            throw new IllegalArgumentException("query is required (max 500 characters)");
        var query = request.query().strip();
        var cleaned = ProtectedAttributes.strip(query);
        if (cleaned.isBlank())
            throw new IllegalArgumentException(
                    "The search can only use skills, experience and location, not personal characteristics");
        boolean stripped = !cleaned.equals(query);
        var outcome = gateway.chat(new Call<Map<String, Object>>(
                tenantId,
                AiTask.NL_FILTER,
                null,
                ModelGateway.keyOf(cleaned),
                Map.of("query", InputGuard.neutralizeDelimiters(cleaned)),
                400,
                false,
                json -> OutputGuard.nlFilter(json, cleaned),
                out -> Map.of("protectedTermsRemoved", stripped)));
        var result = new LinkedHashMap<>(outcome.value());
        result.put("provenance", outcome.provenance());
        return result;
    }

    // ---- explain fit ----

    private record Explained(List<Statement> now, List<Statement> history) {}

    public ExplainResponse explainFit(String tenantId, ExplainRequest request) {
        if (request == null || request.job() == null || blank(request.job().title()))
            throw new IllegalArgumentException("job.title is required");
        var current = checkedChunks(request.current(), "current", false);
        var history = new ArrayList<ChunkIn>();
        int n = 1;
        for (var chunk : checkedChunks(request.historical(), "historical", false))
            history.add(new ChunkIn(blank(chunk.chunkId()) ? "h" + n++ : chunk.chunkId(), chunk.text()));
        var job = request.job();
        var criteria = new StringBuilder();
        if (job.criteria() != null)
            job.criteria().stream().filter(c -> !blank(c)).limit(12).forEach(c -> criteria.append("- ")
                    .append(oneLine(c, 160))
                    .append('\n'));
        var vars = new LinkedHashMap<String, String>();
        vars.put("title", InputGuard.neutralizeDelimiters(oneLine(job.title(), 160)));
        vars.put(
                "summary",
                InputGuard.neutralizeDelimiters(blank(job.summary()) ? "(none)" : oneLine(job.summary(), 800)));
        vars.put(
                "criteria",
                criteria.isEmpty() ? "- (none given)" : criteria.toString().stripTrailing());
        vars.put("current", format(current));
        vars.put("historical", format(history));
        var currentChunks =
                current.stream().map(c -> new Chunk(c.chunkId(), c.text())).toList();
        var historyChunks =
                history.stream().map(c -> new Chunk(c.chunkId(), c.text())).toList();
        boolean injection = Stream.concat(currentChunks.stream(), historyChunks.stream())
                .anyMatch(c -> InputGuard.suspicious(c.text()));
        var outcome = gateway.chat(new Call<Explained>(
                tenantId,
                AiTask.FIT_EXPLAIN,
                null,
                ModelGateway.keyOf(job.title(), currentChunks.size(), historyChunks.size()),
                vars,
                700,
                false,
                json -> new Explained(
                        OutputGuard.statements(json.get("whyFitNow"), "CURRENT", currentChunks),
                        OutputGuard.statements(json.get("historicalEvidence"), "HISTORICAL", historyChunks)),
                out -> Map.of(
                        "grounded",
                        true,
                        "statements",
                        out.now().size() + out.history().size(),
                        "injectionSuspected",
                        injection)));
        var now = outcome.value().now();
        var past = outcome.value().history();
        return new ExplainResponse(
                now.isEmpty()
                        ? "No supported evidence of fit was found in the current resume."
                        : "From the current resume: " + sentences(now),
                past.isEmpty() ? "No earlier history was found." : "From earlier history: " + sentences(past),
                now.stream().map(AiTaskService::view).toList(),
                past.stream().map(AiTaskService::view).toList(),
                outcome.provenance());
    }

    private static StatementView view(Statement s) {
        return new StatementView(s.statement(), s.source(), s.chunkId(), s.quote());
    }

    private static String sentences(List<Statement> statements) {
        return statements.stream()
                .map(Statement::statement)
                .map(s -> s.endsWith(".") ? s : s + ".")
                .collect(Collectors.joining(" "));
    }

    // ---- helpers ----

    private static String format(List<ChunkIn> chunks) {
        if (chunks.isEmpty()) return "(none)";
        var out = new StringBuilder();
        for (var chunk : chunks) {
            if (!out.isEmpty()) out.append("\n\n");
            out.append("[chunkId: ")
                    .append(oneLine(chunk.chunkId(), 64))
                    .append("]\n")
                    .append(InputGuard.neutralizeDelimiters(
                            InputGuard.redact(chunk.text(), null).text()));
        }
        return out.toString();
    }

    private static List<ChunkIn> checkedChunks(List<ChunkIn> chunks, String field, boolean required) {
        if (chunks == null || chunks.isEmpty()) {
            if (required) throw new IllegalArgumentException(field + " must contain at least one chunk");
            return List.of();
        }
        if (chunks.size() > MAX_CHUNKS)
            throw new IllegalArgumentException(field + " allows at most " + MAX_CHUNKS + " chunks");
        var ids = new LinkedHashSet<String>();
        var out = new ArrayList<ChunkIn>();
        for (var chunk : chunks) {
            if (chunk == null || blank(chunk.text())) throw new IllegalArgumentException("each chunk needs text");
            if (chunk.text().length() > MAX_CHUNK_CHARS)
                throw new IllegalArgumentException("a chunk is longer than " + MAX_CHUNK_CHARS + " characters");
            if (!field.equals("historical") && blank(chunk.chunkId()))
                throw new IllegalArgumentException("each chunk needs a chunkId");
            if (!blank(chunk.chunkId()) && !ids.add(chunk.chunkId()))
                throw new IllegalArgumentException("chunkId " + chunk.chunkId() + " is repeated");
            out.add(chunk);
        }
        return out;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String orNone(String value) {
        return blank(value) ? "(not specified)" : oneLine(value, 160);
    }

    private static String oneLine(String value, int max) {
        var line = value == null ? "" : value.replaceAll("\\s+", " ").strip();
        return line.length() <= max ? line : line.substring(0, max);
    }
}
