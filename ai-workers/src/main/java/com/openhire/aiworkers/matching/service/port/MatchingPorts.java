package com.openhire.aiworkers.matching.service.port;

import com.openhire.aiworkers.matching.domain.Assessment;
import com.openhire.aiworkers.matching.domain.Judgement;
import com.openhire.aiworkers.matching.domain.MatchingRequest;
import com.openhire.aiworkers.matching.domain.StoredChunk;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class MatchingPorts {
    private MatchingPorts() {}

    /** The model gateway. Any exception means the AI chain is unavailable and the caller falls back. */
    public interface AiGateway {
        List<double[]> embed(String tenantId, List<String> inputs);

        Judgement judge(
                String tenantId, MatchingRequest.Criterion criterion, List<ChunkText> chunks, String rubricVersion);
    }

    public record ChunkText(String chunkId, String text) {}

    public interface ChunkStore {
        List<String> chunkIds(String tenantId, String resumeId);

        /** Replaces every stored chunk of the resume, so a re-run never leaves stale or duplicate chunks. */
        void replace(
                String tenantId, String candidateId, String applicationId, String resumeId, List<StoredChunk> chunks);

        /** Tenant and candidate scoped: no other candidate's chunk can be returned. */
        List<StoredChunk> chunks(String tenantId, String candidateId, String resumeId);
    }

    public interface CriterionVectors {
        Optional<double[]> find(String tenantId, String textHash);

        void put(String tenantId, String textHash, double[] vector);
    }

    public interface AssessmentStore {
        boolean commandProcessed(String tenantId, String eventId);

        boolean hasComplete(String tenantId, String applicationId, String resumeId, int jobVersion, String rubricHash);

        /** Stores the version and its outgoing event atomically; a COMPLETE supersedes earlier versions. */
        void save(Assessment assessment, Map<String, Object> event);
    }

    public record RetryJob(String id, MatchingRequest request, int attempt, Instant firstFailedAt) {}

    public interface RetryStore {
        /** Creates the retry unless one already exists for the same work. */
        void schedule(String id, MatchingRequest request, Instant firstFailedAt, Instant nextAttemptAt);

        Optional<RetryJob> claimNext(Instant now, Duration lease, String owner);

        void reschedule(String id, int attempt, Instant nextAttemptAt);

        void finish(String id, String state);
    }
}
