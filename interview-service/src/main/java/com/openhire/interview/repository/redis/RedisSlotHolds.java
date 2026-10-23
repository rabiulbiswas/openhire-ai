package com.openhire.interview.repository.redis;

import com.openhire.interview.domain.SlotFinder.Interval;
import com.openhire.interview.service.port.InterviewPorts.Hold;
import com.openhire.interview.service.port.InterviewPorts.SlotHolds;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Reservation pattern from the design: offered slots are held for a bounded time.
 * {@code hold:slot:{tenant}:{roundId}} stores the offer; per-interviewer sorted sets let other rounds
 * avoid proposing the same times while the hold lasts. The booking ledger remains the final guard.
 */
@Component
class RedisSlotHolds implements SlotHolds {
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Clock clock;

    RedisSlotHolds(StringRedisTemplate redis, ObjectMapper json, Clock clock) {
        this.redis = redis;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public Hold hold(
            String tenantId,
            String roundId,
            Collection<String> interviewerKeys,
            List<Instant> slots,
            Duration length,
            Duration ttl) {
        clear(tenantId, roundId, interviewerKeys);
        var taken = heldByOtherRounds(tenantId, interviewerKeys, roundId);
        var free = slots.stream()
                .filter(start -> taken.stream().noneMatch(value -> value.overlaps(start, start.plus(length))))
                .toList();
        var expiresAt = clock.instant().plus(ttl);
        for (var key : interviewerKeys) {
            var panelKey = panelKey(tenantId, key);
            for (var start : free)
                redis.opsForZSet().add(panelKey, member(roundId, start, start.plus(length)), expiresAt.toEpochMilli());
            redis.expire(panelKey, ttl.plusMinutes(1));
        }
        var hold = new Hold(free, expiresAt);
        redis.opsForValue()
                .set(
                        slotKey(tenantId, roundId),
                        json.writeValueAsString(Map.of(
                                "slots",
                                free.stream().map(Instant::toString).toList(),
                                "expiresAt",
                                expiresAt.toString())),
                        ttl);
        return hold;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<Hold> current(String tenantId, String roundId) {
        var raw = redis.opsForValue().get(slotKey(tenantId, roundId));
        if (raw == null) return Optional.empty();
        var value = json.readValue(raw, Map.class);
        var expiresAt = Instant.parse((String) value.get("expiresAt"));
        if (!expiresAt.isAfter(clock.instant())) return Optional.empty();
        var slots =
                ((List<String>) value.get("slots")).stream().map(Instant::parse).toList();
        return Optional.of(new Hold(slots, expiresAt));
    }

    @Override
    public List<Interval> heldByOtherRounds(String tenantId, Collection<String> interviewerKeys, String roundId) {
        var now = clock.instant().toEpochMilli();
        var result = new ArrayList<Interval>();
        for (var key : interviewerKeys) {
            var panelKey = panelKey(tenantId, key);
            redis.opsForZSet().removeRangeByScore(panelKey, 0, now);
            var members = redis.opsForZSet().rangeByScore(panelKey, now, Double.MAX_VALUE);
            if (members == null) continue;
            for (var member : members) {
                var parts = member.split("\\|");
                if (parts.length != 3 || parts[0].equals(roundId)) continue;
                result.add(new Interval(
                        Instant.ofEpochSecond(Long.parseLong(parts[1])),
                        Instant.ofEpochSecond(Long.parseLong(parts[2]))));
            }
        }
        return result;
    }

    @Override
    public void clear(String tenantId, String roundId, Collection<String> interviewerKeys) {
        redis.delete(slotKey(tenantId, roundId));
        for (var key : interviewerKeys) {
            var panelKey = panelKey(tenantId, key);
            var members = redis.opsForZSet().range(panelKey, 0, -1);
            if (members == null) continue;
            var mine = members.stream()
                    .filter(member -> member.startsWith(roundId + "|"))
                    .toArray();
            if (mine.length > 0) redis.opsForZSet().remove(panelKey, mine);
        }
    }

    private static String member(String roundId, Instant start, Instant end) {
        return roundId + "|" + start.getEpochSecond() + "|" + end.getEpochSecond();
    }

    private static String slotKey(String tenantId, String roundId) {
        return "hold:slot:" + tenantId + ":" + roundId;
    }

    private static String panelKey(String tenantId, String interviewerKey) {
        return "hold:panel:" + tenantId + ":" + interviewerKey;
    }
}
