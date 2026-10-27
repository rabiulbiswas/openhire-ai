package com.openhire.offer.repository.mongo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.openhire.offer.domain.Offer;
import com.openhire.offer.domain.OfferTypes.Status;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

class OfferPaginationQueryTest {
    @Test
    @SuppressWarnings("unchecked")
    void databaseQueryPinsTenantStatusLiteralSearchAndStableCursorWithBoundedLimit() {
        var mongo = mock(MongoTemplate.class);
        var repository = new MongoOfferRepository(mongo);
        var at = Instant.parse("2026-10-25T00:00:00Z");
        repository.page("tenant-a", Status.DRAFT, ".*", at, "offer-z", false, 26);
        var captor = ArgumentCaptor.forClass(Query.class);
        verify(mongo).find(captor.capture(), eq(Offer.class));
        var query = captor.getValue();
        assertThat(query.getLimit()).isEqualTo(26);
        assertThat(query.getSkip()).isZero();
        assertThat(query.getSortObject()).isEqualTo(new Document("createdAt", -1).append("_id", -1));
        var filters = (List<Document>) query.getQueryObject().get("$and");
        assertThat(filters.get(0)).isEqualTo(new Document("tenantId", "tenant-a"));
        assertThat(filters.get(1)).isEqualTo(new Document("status", Status.DRAFT));
        var search = (List<Document>) filters.get(2).get("$or");
        var pattern = (Pattern) search.get(0).get("candidateName");
        assertThat(pattern.matcher("name.*example").find()).isTrue();
        assertThat(pattern.matcher("anything").find()).isFalse();
        var cursor = (List<Document>) filters.get(3).get("$or");
        assertThat(cursor.get(0)).isEqualTo(new Document("createdAt", new Document("$lt", at)));
        repository.page("tenant-a", null, "", at, "offer-z", true, 26);
        verify(mongo, times(2)).find(captor.capture(), eq(Offer.class));
        assertThat(captor.getValue().getSortObject()).isEqualTo(new Document("createdAt", 1).append("_id", 1));
    }
}
