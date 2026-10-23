package com.openhire.interview.service.port;

import com.openhire.interview.domain.InterviewLoop;
import com.openhire.interview.domain.InterviewTypes.Competency;
import com.openhire.interview.domain.InterviewTypes.RoundPlan;
import com.openhire.interview.domain.SlotFinder.Interval;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Outbound ports of the interview application layer. */
public final class InterviewPorts {
    private InterviewPorts() {}

    public interface LoopRepository {
        InterviewLoop save(InterviewLoop loop);

        Optional<InterviewLoop> findByApplication(String tenantId, String applicationId);

        Optional<InterviewLoop> findByRound(String tenantId, String roundId);

        List<InterviewLoop> findWithRoundsStartingBetween(String tenantId, Instant from, Instant to);

        List<InterviewLoop> findWithUnscheduledRounds(String tenantId);

        List<InterviewLoop> findForInterviewer(String tenantId, String email, String name);

        List<InterviewLoop> findNeedingSweep(Instant now, int limit);
    }

    /** Thrown when an interviewer already holds a booking that overlaps the requested time. */
    public static final class BookingConflict extends IllegalStateException {
        public BookingConflict(String message) {
            super(message);
        }
    }

    public interface BookingLedger {
        /** Reserves the interval for every interviewer atomically per block, or throws {@link BookingConflict}. */
        void reserve(String tenantId, String roundId, Collection<String> interviewerKeys, Instant start, Instant end);

        void release(String tenantId, String roundId);

        /** Deletes this round's blocks outside the given interviewers and interval, keeping the rest reserved. */
        void retain(String tenantId, String roundId, Collection<String> interviewerKeys, Instant start, Instant end);

        List<Interval> busy(String tenantId, Collection<String> interviewerKeys, Instant from, Instant to);
    }

    public record Hold(List<Instant> slots, Instant expiresAt) {}

    public interface SlotHolds {
        /** Holds the slots for this round; slots another round already holds for a shared interviewer are dropped. */
        Hold hold(
                String tenantId,
                String roundId,
                Collection<String> interviewerKeys,
                List<Instant> slots,
                Duration length,
                Duration ttl);

        Optional<Hold> current(String tenantId, String roundId);

        List<Interval> heldByOtherRounds(String tenantId, Collection<String> interviewerKeys, String roundId);

        void clear(String tenantId, String roundId, Collection<String> interviewerKeys);
    }

    public record ApplicationInfo(
            String applicationId,
            String candidateId,
            String candidateName,
            String jobId,
            String jobTitle,
            String stage) {}

    public interface ApplicationDirectory {
        ApplicationInfo application(String tenantId, String applicationId);
    }

    public record JobPlan(String jobId, String title, List<RoundPlan> rounds, List<Competency> competencies) {}

    public interface JobDirectory {
        JobPlan interviewPlan(String tenantId, String jobId);
    }

    public record TokenClaims(String tenantId, String roundId, Instant expiresAt) {}

    public interface SchedulingTokens {
        String issue(String tenantId, String roundId, Instant expiresAt);

        /** Returns the claims of a valid, unexpired token or throws {@link IllegalArgumentException}. */
        TokenClaims verify(String token, Instant now);
    }

    public interface SweepLock {
        boolean tryAcquire(String name, Duration lease);
    }
}
