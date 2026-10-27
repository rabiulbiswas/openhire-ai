package com.openhire.identity.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.mongodb.client.MongoClients;
import com.openhire.identity.domain.IdentityModels.*;
import com.openhire.identity.service.port.IdentityProvider;
import com.openhire.identity.service.port.IdentityRepositories.*;
import com.openhire.platform.security.CandidateIdentity;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.server.ResponseStatusException;

class CandidateAccountsMongoTest {
    private com.mongodb.client.MongoClient client;
    private MongoTemplate mongo;
    private CandidateAccounts accounts;
    private Members members;
    private IdentityProvider provider;
    private CandidateCodeMail mail;
    private MutableClock clock;
    private final Map<String, Member> saved = new ConcurrentHashMap<>();
    @BeforeEach void setup() {
        String uri = System.getenv("OPENHIRE_TEST_MONGO");
        Assumptions.assumeTrue(uri != null && !uri.isBlank(), "Opt-in isolated Mongo tests");
        client = MongoClients.create(uri);
        mongo = new MongoTemplate(client, "oh_candidate_registration_test_" + UUID.randomUUID().toString().replace("-", ""));
        var organizations = mock(Organizations.class); members = mock(Members.class); provider = mock(IdentityProvider.class); mail = mock(CandidateCodeMail.class);
        when(organizations.findById(anyString())).thenAnswer(call -> Optional.of(new Organization(call.getArgument(0), "Fixture", "fixture", OrganizationStatus.ACTIVE, Instant.now())));
        when(members.findByEmail(anyString(), anyString())).thenAnswer(call -> saved.values().stream().filter(member -> member.organizationId().equals(call.getArgument(0)) && member.email().equals(call.getArgument(1))).findFirst());
        when(members.findMemberById(anyString(), anyString())).thenAnswer(call -> Optional.ofNullable(saved.get(call.getArgument(1))).filter(member -> member.organizationId().equals(call.getArgument(0))));
        when(members.save(any())).thenAnswer(call -> { Member member = call.getArgument(0); saved.put(member.id(), member); return member; });
        when(members.updateDisplayName(anyString(), anyString(), anyString())).thenAnswer(call -> { var old = saved.get(call.getArgument(1)); if (old == null) return false; saved.put(old.id(), new Member(old.id(), old.organizationId(), old.email(), call.getArgument(2), old.status(), old.roles(), old.createdAt())); return true; });
        when(provider.registerVerifiedCandidate(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn("candidate-id");
        clock = new MutableClock();
        accounts = new CandidateAccounts(mongo, organizations, members, provider, mail, clock, "test-verification-secret-with-at-least-32-characters");
        accounts.indexes();
    }
    @AfterEach void cleanup() { if (mongo != null) { assertThat(mongo.getDb().getName()).startsWith("oh_candidate_registration_test_"); mongo.getDb().drop(); } if (client != null) client.close(); }
    private Document begin() { return accounts.begin("tenant-a", "Test Candidate", "candidate@example.test", "Kolkata, India", "+91 99000 12345"); }
    private String code() { var codes = ArgumentCaptor.forClass(String.class); verify(mail, atLeastOnce()).send(eq("candidate@example.test"), eq("Fixture"), codes.capture()); return codes.getValue(); }
    private void verifyCode(Document challenge, String code) { accounts.verify("tenant-a", challenge.getString("registrationId"), code, "Candidate password 123!", "Candidate password 123!"); }

    @Test void verifiesOnceCreatesCandidateOnlyAndStoresNoPasswordOrPlaintextCode() {
        var challenge = begin(); String code = code();
        assertThat(saved).isEmpty(); assertThat(challenge).doesNotContainKeys("code", "password");
        assertThat(mongo.findAll(Document.class, "candidate_registrations").getFirst().toJson()).doesNotContain("Candidate password", "\"code\":");
        verifyCode(challenge, code);
        assertThat(saved.get("candidate-id").roles()).containsExactly("CANDIDATE");
        assertThat(saved.get("candidate-id").status()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(accounts.profile(new CandidateIdentity("tenant-a", "candidate-id", "candidate@example.test"))).containsEntry("location", "Kolkata, India").containsEntry("phone", "+91 99000 12345");
        assertThatThrownBy(() -> verifyCode(challenge, code)).isInstanceOf(ResponseStatusException.class);
        verify(provider, times(1)).registerVerifiedCandidate(anyString(), anyString(), anyString(), anyString(), anyString());
    }
    @Test void limitsAttemptsAndExpiresCodes() {
        var challenge = begin(); String actual = code(), wrong = actual.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 5; i++) assertThatThrownBy(() -> verifyCode(challenge, wrong)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> verifyCode(challenge, actual)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(provider);
        clock.advance(61); var next = begin(); String fresh = code(); clock.advance(601);
        assertThatThrownBy(() -> verifyCode(next, fresh)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void bindsCodesToTenantAndRotatesThemOnResendWithCooldown() {
        var first = begin(); String old = code();
        assertThatThrownBy(this::begin).isInstanceOf(ResponseStatusException.class).hasMessageContaining("429");
        assertThatThrownBy(() -> accounts.verify("tenant-b", first.getString("registrationId"), old, "Candidate password 123!", "Candidate password 123!")).isInstanceOf(ResponseStatusException.class);
        clock.advance(61); var next = begin(); String fresh = code();
        assertThatThrownBy(() -> verifyCode(first, old)).isInstanceOf(ResponseStatusException.class);
        verifyCode(next, fresh);
    }
    @Test void existingStaffAccountCannotBeRegisteredOrHaveRolesChanged() {
        saved.put("staff", new Member("staff", "tenant-a", "candidate@example.test", "Staff", MemberStatus.ACTIVE, Set.of("ORG_ADMIN"), clock.instant()));
        var challenge = begin();
        verifyNoInteractions(mail, provider);
        assertThatThrownBy(() -> verifyCode(challenge, "000000")).isInstanceOf(ResponseStatusException.class);
        assertThat(saved.get("staff").roles()).containsExactly("ORG_ADMIN");
    }
    @Test void concurrentVerificationProvisionsAtMostOneAccount() throws Exception {
        var challenge = begin(); String actual = code();
        try (var pool = Executors.newFixedThreadPool(6)) {
            var tasks = new ArrayList<Callable<Boolean>>();
            for (int i = 0; i < 6; i++) tasks.add(() -> { try { verifyCode(challenge, actual); return true; } catch (ResponseStatusException denied) { return false; } });
            long successful = 0; for (var result : pool.invokeAll(tasks)) if (result.get()) successful++;
            assertThat(successful).isEqualTo(1);
        }
        verify(provider, times(1)).registerVerifiedCandidate(anyString(), anyString(), anyString(), anyString(), anyString());
    }
    @Test void profileUpdateCannotChangeEmailOrAnotherTenant() {
        var challenge = begin(); verifyCode(challenge, code());
        var who = new CandidateIdentity("tenant-a", "candidate-id", "candidate@example.test");
        assertThat(accounts.update(who, "Updated Candidate", "Pune, India", "+91 99999 12345")).containsEntry("name", "Updated Candidate").containsEntry("email", "candidate@example.test");
        assertThatThrownBy(() -> accounts.profile("tenant-b", "candidate-id")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> accounts.update(who, "Updated", "Pune", "not-a-phone")).isInstanceOf(IllegalArgumentException.class);
    }
    static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-26T01:00:00Z");
        void advance(long seconds) { now = now.plusSeconds(seconds); }
        public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
