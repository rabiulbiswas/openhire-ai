package com.openhire.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.mongodb.client.result.UpdateResult;
import com.openhire.identity.domain.IdentityModels.Member;
import com.openhire.identity.repository.mongo.MongoIdentityRepository;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

class MemberProfilePersistenceTest {
    @Test
    void renameUpdatesOnlyTheNameAndIsScopedToAnEnabledTenantMember() {
        var mongo = mock(MongoTemplate.class);
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(Member.class), eq("users")))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));
        assertThat(new MongoIdentityRepository(mongo).updateDisplayName("tenant-a", "user-a", "Current Name"))
                .isTrue();
        var query = ArgumentCaptor.forClass(Query.class);
        var update = ArgumentCaptor.forClass(Update.class);
        verify(mongo).updateFirst(query.capture(), update.capture(), eq(Member.class), eq("users"));
        assertThat(query.getValue().getQueryObject())
                .containsEntry("organizationId", "tenant-a")
                .containsEntry("_id", "user-a")
                .containsKey("status");
        assertThat(update.getValue().getUpdateObject())
                .isEqualTo(new Document("$set", new Document("displayName", "Current Name")));
        verifyNoMoreInteractions(mongo);
    }
}
