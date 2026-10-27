package com.openhire.integration.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.mongodb.client.MongoClients;
import com.openhire.integration.security.FileIntegrationSecrets;
import com.openhire.platform.audit.AuditRecorder;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.server.ResponseStatusException;

@EnabledIfEnvironmentVariable(named = "OPENHIRE_RUN_MONGO_TESTS", matches = "true")
class JobPortalsMongoTest {
    @Test
    void confirmedPublishConcurrentOffRemovalCloseAndUnavailableProvider() {
        String database = "oh_jobportal_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var client = MongoClients.create("mongodb://localhost:27017/?serverSelectionTimeoutMS=3000")) {
            try {
                var mongo = new MongoTemplate(client, database);
                var jobs = mock(JobPortalJobCatalog.class);
                var secrets = mock(FileIntegrationSecrets.class);
                var provider = mock(JobPortalProvider.class);
                var open = Map.<String, Object>of(
                        "status", "OPEN", "approvedVersion", 1, "currentVersion", Map.of("version", 1));
                when(jobs.get("t", "job")).thenReturn(open);
                when(provider.provider()).thenReturn("LINKEDIN");
                when(secrets.read("secret://t/key")).thenReturn("private");
                when(provider.verify("private")).thenReturn(true);
                var service = new JobPortals(mongo, jobs, secrets, List.of(provider), mock(AuditRecorder.class), true);
                service.indexes();
                assertThrows(
                        ResponseStatusException.class,
                        () -> service.configure("t", "a", "LINKEDIN", "secret://other/key", true, 0));
                service.configure("t", "a", "LINKEDIN", "secret://t/key", true, 0);
                assertFalse(service.configure("t", "a", "NAUKRI", "secret://t/key", true, 0)
                        .verified());
                assertThrows(ResponseStatusException.class, () -> service.desire("t", "a", "job", "NAUKRI", true, 0));
                service.desire("t", "a", "job", "LINKEDIN", true, 0);
                assertThrows(ResponseStatusException.class, () -> service.desire("t", "a", "job", "LINKEDIN", true, 0));
                assertThrows(
                        ResponseStatusException.class,
                        () -> service.configure("t", "a", "LINKEDIN", "secret://t/key", false, 1));
                when(provider.reconcile(eq("private"), anyString(), isNull(), eq(open), eq(true)))
                        .thenAnswer(call -> {
                            service.desire("t", "a", "job", "LINKEDIN", false, 1);
                            return new JobPortalProvider.Result(true, "remote");
                        });
                service.reconcile();
                assertEquals("QUEUED", service.postings("t", "job").getFirst().status());
                var rows = mongo.getCollection("job_portal_postings");
                assertEquals("remote", rows.find().first().getString("remoteId"));
                rows.updateOne(new Document("job", "job"), new Document("$set", new Document("due", new Date(0))));
                when(provider.reconcile("private", "t:job:LINKEDIN", "remote", open, false))
                        .thenReturn(new JobPortalProvider.Result(true, "remote"));
                service.reconcile();
                assertEquals("REMOVED", service.postings("t", "job").getFirst().status());
                service.desire("t", "a", "job", "LINKEDIN", true, 2);
                when(provider.reconcile("private", "t:job:LINKEDIN", "remote", open, true))
                        .thenReturn(new JobPortalProvider.Result(false, "remote"));
                service.reconcile();
                assertEquals(
                        "PUBLISHING", service.postings("t", "job").getFirst().status());
                rows.updateOne(new Document("job", "job"), new Document("$set", new Document("due", new Date(0))));
                when(provider.reconcile("private", "t:job:LINKEDIN", "remote", open, true))
                        .thenReturn(new JobPortalProvider.Result(true, "remote"));
                service.reconcile();
                assertEquals(
                        "PUBLISHED", service.postings("t", "job").getFirst().status());
                var closed = Map.<String, Object>of("status", "CLOSED");
                when(jobs.get("t", "job")).thenReturn(closed);
                rows.updateOne(new Document("job", "job"), new Document("$set", new Document("due", new Date(0))));
                when(provider.reconcile("private", "t:job:LINKEDIN", "remote", closed, false))
                        .thenReturn(new JobPortalProvider.Result(true, "remote"));
                service.reconcile();
                assertEquals("REMOVED", service.postings("t", "job").getFirst().status());
                assertFalse(service.postings("t", "job").getFirst().enabled());
            } finally {
                client.getDatabase(database).drop();
            }
        }
    }
}
