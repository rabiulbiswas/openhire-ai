package com.openhire.offer.repository.mongo;

import static org.assertj.core.api.Assertions.*;

import com.openhire.offer.domain.OfferTypes.Viewer;
import java.util.List;
import java.util.Set;
import org.bson.Document;
import org.junit.jupiter.api.Test;

class OfferApprovalQueryTest {
    @Test
    void recruiterGetsAnEmptyAssignedQueueRatherThanAnInvalidEmptyOrQuery() {
        var query = MongoOfferRepository.approvalQuery(
                "tenant-a", new Viewer("recruiter", null, null, Set.of("RECRUITER")), "waiting_on_me");
        assertThat(query.getQueryObject())
                .containsEntry("tenantId", "tenant-a")
                .containsEntry("_id", "__no_assigned_approval__");
    }

    @Test
    @SuppressWarnings("unchecked")
    void waitingQueryPinsTenantCurrentRoleAndExcludesOwnerAndPreviousDeciders() {
        var viewer = new Viewer("admin-1", null, null, Set.of("ORG_ADMIN"));
        var query = MongoOfferRepository.approvalQuery("tenant-a", viewer, "waiting_on_me")
                .getQueryObject();
        var all = (List<Document>) query.get("$and");
        assertThat(all.get(0)).isEqualTo(new Document("tenantId", "tenant-a"));
        var filters = (List<Document>) all.get(2).get("$and");
        assertThat(filters.get(1)).isEqualTo(new Document("ownerId", new Document("$ne", "admin-1")));
        assertThat(query.toString()).contains("approvalAssignees.FINANCE", "admin-1");
        assertThat(filters.get(3)).isEqualTo(new Document("approvalSteps.decidedBy", new Document("$ne", "admin-1")));
        var match = filters.get(4).get("approvalSteps", Document.class).get("$elemMatch", Document.class);
        assertThat(match.getString("status")).isEqualTo("CURRENT");
        assertThat(match.get("role", Document.class).getList("$in", String.class))
                .containsExactly("FINANCE");
        assertThatThrownBy(() -> MongoOfferRepository.approvalQuery("tenant-a", viewer, "unknown"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
