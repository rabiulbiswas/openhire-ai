package com.openhire.interview.service;

import com.openhire.interview.domain.InterviewLoop;
import com.openhire.interview.domain.InterviewRound;
import com.openhire.interview.domain.InterviewTypes.*;
import com.openhire.interview.service.port.InterviewPorts.LoopRepository;
import com.openhire.platform.web.ResourceNotFoundException;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class InterviewAssistant {
    public interface Gateway {
        default Map<String, Object> resume(String tenant, InterviewLoop loop) {
            return Map.of();
        }

        Map<String, Object> generate(
                String tenant,
                InterviewLoop loop,
                InterviewRound round,
                String mode,
                List<Map<String, String>> sources);
    }

    private final LoopRepository loops;
    private final Gateway gateway;
    private final Clock clock;
    private com.openhire.support.privacy.Tombstones tombstones;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTombstones(com.openhire.support.privacy.Tombstones tombstones) {
        this.tombstones = tombstones;
    }

    public InterviewAssistant(LoopRepository loops, Gateway gateway, Clock clock) {
        this.loops = loops;
        this.gateway = gateway;
        this.clock = clock;
    }

    public Map<String, Object> kit(String tenant, String id, Viewer viewer) {
        var loop = roundLoop(tenant, id);
        var round = loop.round(id);
        readable(round, viewer);
        var result = new LinkedHashMap<String, Object>();
        result.put("roundId", id);
        result.put("candidateName", loop.candidateName());
        result.put("jobTitle", loop.jobTitle());
        result.put("roundName", round.name());
        result.put("durationMin", round.durationMin());
        result.put("status", round.status().name());
        result.put(
                "panel",
                round.panel().stream().map(p -> Map.of("name", p.name())).toList());
        result.put("competencies", round.competencies());
        result.put("meetingUrl", round.meetingUrl());
        result.put(
                "feedbackDueAt",
                round.feedbackDueAt() == null ? null : round.feedbackDueAt().toString());
        result.put("canSubmit", canDraft(loop, round, viewer));
        result.put("onPanel", round.panelMember(viewer) != null);
        return result;
    }

    public Map<String, Object> resume(String tenant, String id, Viewer viewer) {
        var loop = roundLoop(tenant, id);
        readable(loop.round(id), viewer);
        var result = gateway.resume(tenant, loop);
        readable(roundLoop(tenant, id).round(id), viewer);
        return result;
    }

    public Map<String, Object> questions(String tenant, String id, Viewer viewer) {
        var loop = roundLoop(tenant, id);
        var round = loop.round(id);
        readable(round, viewer);
        if (loop.closed() || Set.of(RoundStatus.CANCELLED, RoundStatus.NO_SHOW).contains(round.status()))
            throw new IllegalStateException("This round is not active");
        var result = gateway.generate(tenant, loop, round, "QUESTIONS", List.of());
        readable(roundLoop(tenant, id).round(id), viewer);
        return result;
    }

    public Map<String, Object> feedback(String tenant, String id, Viewer viewer, String notes) {
        if (notes == null || notes.isBlank() || notes.length() > 6000)
            throw new IllegalArgumentException("Provide interview evidence between 1 and 6000 characters");
        var loop = roundLoop(tenant, id);
        var round = loop.round(id);
        if (round.panelMember(viewer) == null)
            throw new InterviewApplicationService.AccessDenied("Only assigned interviewers can draft feedback");
        if (!canDraft(loop, round, viewer))
            throw new IllegalStateException("Feedback opens when the round starts and locks after submission");
        var result = gateway.generate(
                tenant, loop, round, "FEEDBACK", List.of(Map.of("sourceId", "own-notes", "text", notes.trim())));
        var latest = roundLoop(tenant, id);
        if (!canDraft(latest, latest.round(id), viewer))
            throw new IllegalStateException("This scorecard is no longer editable");
        return result;
    }

    public Map<String, Object> debrief(String tenant, String applicationId, Viewer viewer) {
        var loop = loops.findByApplication(tenant, applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Interview loop not found"));
        available(tenant, loop);
        if (!loop.canReadDebrief(viewer))
            throw new InterviewApplicationService.AccessDenied("Submit your scorecards before viewing panel feedback");
        var sources = new ArrayList<Map<String, String>>();
        int remaining = 18000;
        for (var round : loop.rounds())
            for (var card : round.scorecards()) {
                if (sources.size() == 20 || remaining <= 0) break;
                int size = Math.min(remaining, Math.min(6000, card.evidence().length()));
                sources.add(Map.of(
                        "sourceId",
                        "R" + round.seq() + "-" + (sources.size() + 1),
                        "text",
                        card.evidence().substring(0, size)));
                remaining -= size;
            }
        if (sources.isEmpty())
            throw new IllegalStateException("Submit at least one scorecard before generating a debrief");
        var result = gateway.generate(tenant, loop, loop.rounds().getFirst(), "DEBRIEF", sources);
        var latest = loops.findByApplication(tenant, applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Interview loop not found"));
        available(tenant, latest);
        if (!latest.canReadDebrief(viewer))
            throw new InterviewApplicationService.AccessDenied("Panel feedback is no longer available");
        return result;
    }

    private boolean canDraft(InterviewLoop loop, InterviewRound round, Viewer viewer) {
        var member = round.panelMember(viewer);
        return !loop.closed()
                && member != null
                && round.scorecardOf(member.key()) == null
                && round.slot() != null
                && !round.slot().start().isAfter(clock.instant())
                && Set.of(RoundStatus.SCHEDULED, RoundStatus.COMPLETED, RoundStatus.FEEDBACK_OVERDUE)
                        .contains(round.status());
    }

    private InterviewLoop roundLoop(String tenant, String id) {
        var loop = loops.findByRound(tenant, id)
                .orElseThrow(() -> new ResourceNotFoundException("Interview round not found"));
        available(tenant, loop);
        return loop;
    }

    private void available(String tenant, InterviewLoop loop) {
        if (tombstones != null && tombstones.isErased(tenant, loop.candidateId()))
            throw new ResourceNotFoundException("Candidate is no longer available");
    }

    private static void readable(InterviewRound round, Viewer viewer) {
        if (!viewer.coordinator() && round.panelMember(viewer) == null)
            throw new InterviewApplicationService.AccessDenied("You are not assigned to this round");
    }
}
