package com.openhire.resumeprocessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openhire.support.privacy.PrivacyParticipant.Status;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrivacyDataTest {
    @TempDir
    Path dir;

    private final InMemoryMongo mongo = new InMemoryMongo();
    private final com.openhire.support.privacy.Tombstones tombstones = InMemoryMongo.tombstones();
    private PrivacyData data;

    private static Document resume(String id, String tenant, String application, String name) {
        return new Document("_id", id)
                .append("tenantId", tenant)
                .append("applicationId", application)
                .append("name", name)
                .append("mime", "application/pdf")
                .append("sha256", "digest")
                .append("status", "DONE")
                .append("text", "Experience at Acme for " + name)
                .append("sections", new Document("summary", "text"))
                .append("_events", List.of(new Document("eventId", "e")));
    }

    @BeforeEach
    void rows() throws Exception {
        ApplicationLookup lookup = (tenant, candidate) ->
                tenant.equals("t1") && candidate.equals("cand-1") ? List.of("app-1", "app-2") : List.of();
        data = new PrivacyData(mongo.template, lookup, tombstones, dir.toString());
        mongo.put("resumes", resume("r1", "t1", "app-1", "asha.pdf"));
        mongo.put("resumes", resume("r2", "t1", "app-3", "ben.pdf"));
        mongo.put("resumes", resume("r3", "t2", "app-1", "other-tenant.pdf"));
        for (var id : List.of("r1", "r2", "r3")) Files.writeString(dir.resolve(id), "binary " + id);
    }

    @Test
    void exportListsMetadataAndTextButNeitherFileNorDigest() {
        var section = data.export("t1", "cand-1");

        var resumes = (List<?>) section.get("resumes");
        assertThat(resumes).hasSize(1);
        var item = (Map<?, ?>) resumes.getFirst();
        assertThat(item.get("name")).isEqualTo("asha.pdf");
        assertThat(item.get("extractedText")).isEqualTo("Experience at Acme for asha.pdf");
        assertThat(item.get("sizeBytes")).isEqualTo(9L);
        assertThat(item.keySet().stream().map(Object::toString)).doesNotContain("sha256", "_events", "sections");
    }

    @Test
    void erasureDeletesTheFileAndTheRecordOnly_forThisCandidate() {
        var outcome = data.erase("t1", "req-1", "cand-1");

        assertThat(outcome.status()).isEqualTo(Status.ERASED);
        assertThat(outcome.counts()).containsEntry("resumes", 1).containsEntry("files", 1);
        assertThat(dir.resolve("r1")).doesNotExist();
        assertThat(mongo.one("resumes", "r1")).isNull();
        assertThat(dir.resolve("r2")).exists();
        assertThat(mongo.one("resumes", "r2")).isNotNull();
        assertThat(dir.resolve("r3")).exists();
        assertThat(mongo.collection("resumes")).hasSize(2);
        assertThat(tombstones.isErased("t1", "app-1")).isTrue();
        assertThat(tombstones.isErased("t1", "cand-1")).isTrue();
    }

    @Test
    void erasingTwiceHoldsNothingTheSecondTime() {
        data.erase("t1", "req-1", "cand-1");

        var again = data.erase("t1", "req-1", "cand-1");

        assertThat(again.status()).isEqualTo(Status.NOTHING_HELD);
    }

    @Test
    void aCandidateWithoutApplicationsHoldsNothing() {
        assertThat(data.erase("t1", "req-1", "nobody").status()).isEqualTo(Status.NOTHING_HELD);
        assertThat(mongo.collection("resumes")).hasSize(3);
    }

    @Test
    void anUploadForAnErasedApplicationIsRefused() throws Exception {
        data.erase("t1", "req-1", "cand-1");
        var pipeline = new ResumePipeline(mongo.template, dir.toString(), "http://tika", "localhost", 3310);
        pipeline.setTombstones(tombstones);

        assertThatThrownBy(() -> pipeline.upload("t1", "app-1", "cv.pdf", "%PDF-1.4 x".getBytes()))
                .hasMessageContaining("erased");
    }
}
