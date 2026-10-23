package com.openhire.identity.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.mongodb.client.MongoClients;
import com.openhire.identity.domain.IdentityModels.Member;
import com.openhire.identity.service.port.EnterpriseDirectory;
import com.openhire.identity.service.port.IdentityRepositories.Members;
import com.openhire.platform.audit.AuditRecorder;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.server.ResponseStatusException;

@EnabledIfEnvironmentVariable(named = "OPENHIRE_RUN_MONGO_TESTS", matches = "true")
class ScimUsersTest {
    @Test
    void lifecycleIsolationConflictAndNoRoleEscalation() {
        String database = "oh_phase8_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var client = MongoClients.create("mongodb://localhost:27017/?serverSelectionTimeoutMS=3000")) {
            try {
                var directory = mock(EnterpriseDirectory.class);
                when(directory.provision(any(), any(), any(), any())).thenReturn("provider-user");
                var members = mock(Members.class);
                when(members.findByEmail(any(), any())).thenReturn(Optional.empty());
                when(members.findMemberById(any(), any())).thenReturn(Optional.empty());
                var service = new ScimUsers(
                        new MongoTemplate(client, database),
                        directory,
                        members,
                        Clock.systemUTC(),
                        mock(AuditRecorder.class));
                service.indexes();
                var input = new ScimUsers.Input("Person@Example.com", "Person", "external-1", true);
                var created = service.create("tenant", "actor", input);
                String id = (String) created.get("id");
                assertEquals("person@example.com", created.get("userName"));
                verify(members).save(argThat((Member member) -> member.roles().isEmpty()));
                assertThrows(ResponseStatusException.class, () -> service.create("tenant", "actor", input));
                assertThrows(ResponseStatusException.class, () -> service.get("other", id));
                assertEquals(
                        1L,
                        service.list("tenant", 1, 10, "userName eq \"person@example.com\"")
                                .get("totalResults"));
                assertThrows(
                        ResponseStatusException.class,
                        () -> service.update("tenant", "actor", id, input, "W/\"0\"", false));
                service.delete("tenant", "actor", id, "W/\"1\"");
                verify(directory).update("tenant", "provider-user", "Person", false);
                assertThrows(ResponseStatusException.class, () -> service.get("tenant", id));
            } finally {
                client.getDatabase(database).drop();
            }
        }
    }
}
