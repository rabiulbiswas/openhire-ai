package com.openhire.talent.service;

import com.openhire.talent.domain.Invitation;
import com.openhire.talent.domain.Narratives;
import com.openhire.talent.domain.ProfileFilter;
import com.openhire.talent.domain.Ranking;
import com.openhire.talent.domain.TalentProfile;
import com.openhire.talent.domain.TalentTypes.Filters;
import com.openhire.talent.domain.Vectors;
import com.openhire.talent.dto.TalentViews.CandidateView;
import com.openhire.talent.dto.TalentViews.PoolHealth;
import com.openhire.talent.dto.TalentViews.SearchResult;
import com.openhire.talent.service.port.TalentPorts.AiGateway;
import com.openhire.talent.service.port.TalentPorts.AiUnavailableException;
import com.openhire.talent.service.port.TalentPorts.InvitationRepository;
import com.openhire.talent.service.port.TalentPorts.ProfileRepository;
import com.openhire.talent.service.port.TalentPorts.VectorStore;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Natural-language and structured search over the consented pool, plus the pool-health numbers. */
@Service
public class SearchService {
    private static final Logger LOG = LoggerFactory.getLogger(SearchService.class);
    private static final int QUERY_LIMIT = 500;

    private final ProfileRepository profiles;
    private final VectorStore vectors;
    private final InvitationRepository invitations;
    private final AiGateway gateway;
    private final TalentSettings settings;
    private final Clock clock;

    public SearchService(
            ProfileRepository profiles,
            VectorStore vectors,
            InvitationRepository invitations,
            AiGateway gateway,
            TalentSettings settings,
            Clock clock) {
        this.profiles = profiles;
        this.vectors = vectors;
        this.invitations = invitations;
        this.gateway = gateway;
        this.settings = settings;
        this.clock = clock;
    }

    /** The model turns the sentence into editable chips; when it is down the sentence becomes a meaning-only search. */
    public SearchResult naturalLanguage(String tenantId, String query) {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("query is required");
        var text = query.strip();
        if (text.length() > QUERY_LIMIT) text = text.substring(0, QUERY_LIMIT);
        Filters filters = Filters.none();
        String semantic = text;
        List<String> chips = List.of("Text search: " + text);
        try {
            var generated = gateway.nlFilter(tenantId, text);
            filters = generated.filters() == null ? Filters.none() : generated.filters();
            if (!blank(generated.semanticQuery())) semantic = generated.semanticQuery();
            chips = generated.chips() == null || generated.chips().isEmpty()
                    ? ProfileFilter.chips(filters)
                    : generated.chips();
        } catch (AiUnavailableException unavailable) {
            LOG.info("Natural-language filter unavailable; searching by meaning only ({})", unavailable.getMessage());
        }
        return run(tenantId, filters, semantic, chips);
    }

    /** Runs a filter the person has edited. */
    public SearchResult search(String tenantId, Filters filters, String semanticQuery) {
        var effective = filters == null ? Filters.none() : filters;
        return run(tenantId, effective, semanticQuery, ProfileFilter.chips(effective));
    }

    private SearchResult run(String tenantId, Filters filters, String semanticQuery, List<String> chips) {
        var now = clock.instant();
        var pool = profiles.searchable(tenantId, now, settings.internalMobility()).stream()
                .filter(profile -> ProfileFilter.matches(profile, filters, now))
                .toList();
        var query = blank(semanticQuery) ? null : semanticQuery.strip();
        float[] vector = null;
        if (query != null) {
            try {
                vector = gateway.embed(tenantId, query);
            } catch (AiUnavailableException unavailable) {
                LOG.info("Query embedding unavailable; matching lexically ({})", unavailable.getMessage());
            }
        }
        var stored = vector == null ? Map.<String, float[]>of() : vectors.all(tenantId);
        var scored = new ArrayList<Map.Entry<TalentProfile, Double>>();
        for (var profile : pool) {
            double semantic = 0.5;
            if (query != null) {
                var own = stored.get(profile.candidateId());
                semantic = vector != null && own != null
                        ? Vectors.cosine(vector, own)
                        : Vectors.lexical(query, profile.summary());
            }
            var ranking = Ranking.rank(profile, semantic, filters.skills(), now);
            scored.add(Map.entry(profile, ranking.score()));
        }
        scored.sort(Map.Entry.<TalentProfile, Double>comparingByValue().reversed());
        var candidates = scored.stream()
                .limit(settings.resultLimit())
                .map(entry -> new CandidateView(
                        entry.getKey().candidateId(),
                        entry.getKey().name(),
                        entry.getKey().displayTitle(),
                        (int) Math.round(100 * entry.getValue()),
                        Narratives.whyFitNow(entry.getKey(), filters.skills()),
                        Narratives.historicalEvidence(entry.getKey()),
                        false))
                .toList();
        return new SearchResult(
                chips,
                filters,
                query == null ? "" : query,
                candidates,
                null,
                null,
                vector == null && query != null ? "LEXICAL" : "EMBEDDING");
    }

    /** Computed from the pool and the invitation log; nothing is stored. */
    public PoolHealth poolHealth(String tenantId) {
        var now = clock.instant();
        var pool = profiles.searchable(tenantId, now, settings.internalMobility());
        int silver = (int) pool.stream().filter(TalentProfile::silverMedalist).count();
        var horizon = now.plus(settings.expiryWarning());
        int expiring = (int) pool.stream()
                .filter(profile -> profile.retentionUntil().isBefore(horizon))
                .count();

        var all = invitations.since(tenantId, Instant.EPOCH);
        var firstInvite = all.stream()
                .collect(Collectors.toMap(
                        Invitation::candidateId, Invitation::invitedAt, (a, b) -> a.isBefore(b) ? a : b));
        var quarterStart = quarterStart(now);
        var invitedThisQuarter = new HashSet<String>();
        all.stream()
                .filter(i -> !i.invitedAt().isBefore(quarterStart))
                .forEach(i -> invitedThisQuarter.add(i.candidateId()));

        int reEngaged = 0;
        int appliedAfterInvite = 0;
        int hired = 0;
        for (var entry : firstInvite.entrySet()) {
            var profile = profiles.find(tenantId, entry.getKey()).orElse(null);
            if (profile == null) continue;
            var after = profile.applications().stream()
                    .filter(ref -> ref.appliedAt() != null && ref.appliedAt().isAfter(entry.getValue()))
                    .toList();
            if (after.isEmpty()) continue;
            appliedAfterInvite++;
            if (after.stream().anyMatch(ref -> "HIRED".equals(ref.stage()))) hired++;
            if (invitedThisQuarter.contains(entry.getKey())) reEngaged++;
        }
        double rate = appliedAfterInvite == 0 ? 0 : Math.round(1000.0 * hired / appliedAfterInvite) / 10.0;
        return new PoolHealth(silver, expiring, reEngaged, rate);
    }

    static Instant quarterStart(Instant now) {
        var date = now.atZone(ZoneOffset.UTC).toLocalDate();
        var first = Month.of(((date.getMonthValue() - 1) / 3) * 3 + 1);
        return LocalDate.of(date.getYear(), first, 1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
