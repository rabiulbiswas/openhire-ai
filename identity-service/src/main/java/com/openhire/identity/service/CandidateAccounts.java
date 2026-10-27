package com.openhire.identity.service;

import com.openhire.identity.domain.IdentityModels.*;
import com.openhire.identity.domain.IdentityPolicy;
import com.openhire.identity.service.port.IdentityProvider;
import com.openhire.identity.service.port.IdentityRepositories.Members;
import com.openhire.identity.service.port.IdentityRepositories.Organizations;
import com.openhire.platform.security.CandidateIdentity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.*;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Candidate-only email challenge and profile lifecycle. Passwords never enter Mongo or mail. */
@Service
public class CandidateAccounts {
    private static final String CHALLENGES = "candidate_registrations", PROFILES = "candidate_profiles";
    private final MongoTemplate mongo;
    private final Organizations organizations;
    private final Members members;
    private final IdentityProvider provider;
    private final CandidateCodeMail mail;
    private final Clock clock;
    private final byte[] secret;
    private final SecureRandom random = new SecureRandom();

    public CandidateAccounts(MongoTemplate mongo, Organizations organizations, Members members, IdentityProvider provider,
            CandidateCodeMail mail, Clock clock, @Value("${openhire.candidates.verification-secret:${openhire.internal.secret}}") String secret) {
        this.mongo = mongo; this.organizations = organizations; this.members = members; this.provider = provider;
        this.mail = mail; this.clock = clock; this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @jakarta.annotation.PostConstruct
    void indexes() {
        mongo.indexOps(CHALLENGES).createIndex(new Index().on("deleteAfter", org.springframework.data.domain.Sort.Direction.ASC).expire(Duration.ZERO));
        mongo.indexOps(CHALLENGES).createIndex(new Index().on("tenantId", org.springframework.data.domain.Sort.Direction.ASC).on("challengeHash", org.springframework.data.domain.Sort.Direction.ASC));
        mongo.indexOps(PROFILES).createIndex(new Index().on("tenantId", org.springframework.data.domain.Sort.Direction.ASC).on("subject", org.springframework.data.domain.Sort.Direction.ASC).unique());
    }

    public Document begin(String tenant, String name, String email, String location, String phone) {
        var org = active(tenant);
        name = clean(name, 2, 200, "Full name"); email = IdentityPolicy.normalizeEmail(email);
        if (email.length() > 254) throw new IllegalArgumentException("Email is too long");
        location = clean(location, 2, 200, "Current location"); phone = phone(phone);
        var now = clock.instant(); var id = digest(tenant + "|" + email);
        try { mongo.upsert(Query.query(Criteria.where("_id").is(id)), new Update().setOnInsert("tenantId", tenant)
                .setOnInsert("sends", 0).setOnInsert("nextSendAt", Date.from(now.minusSeconds(1)))
                .setOnInsert("windowStart", Date.from(now)).setOnInsert("state", "NEW"), CHALLENGES); }
        catch (DuplicateKeyException concurrent) { /* Another request initialized the same rate-limit row. */ }
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id).and("windowStart").lt(Date.from(now.minusSeconds(3600)))) ,
                new Update().set("sends", 0).set("windowStart", Date.from(now)), CHALLENGES);
        var row = mongo.findAndModify(Query.query(Criteria.where("_id").is(id).and("sends").lt(5)
                        .and("nextSendAt").lte(Date.from(now)).and("state").ne("COMPLETING")),
                new Update().inc("sends", 1).set("nextSendAt", Date.from(now.plusSeconds(60)))
                , FindAndModifyOptions.options().returnNew(true), Document.class, CHALLENGES);
        if (row == null) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Please wait before requesting another code. A maximum of five emails can be requested per hour.");
        var token = token(); var code = String.format(Locale.ROOT, "%06d", random.nextInt(1000000));
        var challenge = digest(token);
        boolean available = members.findByEmail(tenant, email).isEmpty();
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id)), new Update().set("challengeHash", challenge)
                .set("codeHash", digest(challenge + "|" + code)).set("expiresAt", Date.from(now.plusSeconds(600)))
                .set("deleteAfter", Date.from(now.plusSeconds(86400))).set("attempts", 0).set("state", available ? "PENDING" : "UNAVAILABLE")
                .set("name", name).set("email", email).set("location", location).set("phone", phone), CHALLENGES);
        if (available) {
            try { mail.send(email, org.name(), code); }
            catch (RuntimeException unavailable) {
                mongo.updateFirst(Query.query(Criteria.where("_id").is(id).and("challengeHash").is(challenge)), new Update().set("state", "SEND_FAILED").unset("codeHash"), CHALLENGES);
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "We could not send the verification email. Please try again shortly.");
            }
        }
        // Same response for existing accounts. Never reveal account existence or return a code.
        return new Document("registrationId", token).append("expiresIn", 600).append("resendAfter", 60);
    }

    public void verify(String tenant, String registration, String code, String password, String confirmation) {
        active(tenant);
        if (password == null || password.length() < 12 || password.length() > 128 || !password.equals(confirmation))
            throw new IllegalArgumentException("Use a password of 12–128 characters and matching confirmation");
        if (registration == null || registration.length() > 200 || code == null || !code.matches("[0-9]{6}")) throw invalid();
        var challenge = digest(registration); var now = clock.instant();
        // Recover a crashed provisioning attempt, without resetting its code or attempt counter.
        mongo.updateFirst(Query.query(Criteria.where("tenantId").is(tenant).and("challengeHash").is(challenge)
                .and("state").is("COMPLETING").and("leaseUntil").lt(Date.from(now))), new Update().set("state", "PENDING"), CHALLENGES);
        var row = mongo.findAndModify(Query.query(Criteria.where("tenantId").is(tenant).and("challengeHash").is(challenge)
                        .and("state").is("PENDING").and("expiresAt").gt(Date.from(now)).and("attempts").lt(5)),
                new Update().inc("attempts", 1), FindAndModifyOptions.options().returnNew(true), Document.class, CHALLENGES);
        if (row == null || !MessageDigest.isEqual(digest(challenge + "|" + code).getBytes(StandardCharsets.US_ASCII),
                row.getString("codeHash").getBytes(StandardCharsets.US_ASCII))) throw invalid();
        var claimed = mongo.findAndModify(Query.query(Criteria.where("_id").is(row.getString("_id"))
                        .and("challengeHash").is(challenge).and("state").is("PENDING").and("codeHash").is(row.getString("codeHash"))),
                new Update().set("state", "COMPLETING").set("leaseUntil", Date.from(now.plusSeconds(120))), Document.class, CHALLENGES);
        if (claimed == null) throw invalid();
        try {
            var existing = members.findByEmail(tenant, row.getString("email"));
            if (existing.isPresent() && (!existing.get().roles().equals(Set.of("CANDIDATE")) || existing.get().status() != MemberStatus.ACTIVE))
                throw new IllegalArgumentException("Use the existing account sign-in or contact the organisation");
            var subject = provider.registerVerifiedCandidate(tenant, row.getString("email"), row.getString("name"), password, challenge);
            if (existing.isPresent() && !existing.get().id().equals(subject)) throw new IllegalArgumentException("Account already exists");
            members.save(new Member(subject, tenant, row.getString("email"), row.getString("name"), MemberStatus.ACTIVE,
                    Set.of("CANDIDATE"), existing.map(Member::createdAt).orElse(now)));
            mongo.upsert(profileQuery(tenant, subject), new Update().set("tenantId", tenant).set("subject", subject)
                    .set("name", row.getString("name")).set("email", row.getString("email"))
                    .set("location", row.getString("location")).set("phone", row.getString("phone")), PROFILES);
            mongo.updateFirst(Query.query(Criteria.where("_id").is(row.getString("_id")).and("challengeHash").is(challenge)),
                    new Update().set("state", "COMPLETE").unset("codeHash").unset("leaseUntil"), CHALLENGES);
        } catch (RuntimeException failure) {
            mongo.updateFirst(Query.query(Criteria.where("_id").is(row.getString("_id")).and("challengeHash").is(challenge)), new Update().set("state", "PENDING"), CHALLENGES);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Registration could not be completed. Try again, or sign in if your account already exists.");
        }
    }

    public Document profile(CandidateIdentity who) { return profile(who.tenantId(), who.subject()); }
    public Document profile(String tenant, String subject) {
        active(tenant);
        var member = members.findMemberById(tenant, subject).filter(value -> value.status() == MemberStatus.ACTIVE && value.roles().equals(Set.of("CANDIDATE")))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Candidate membership is unavailable"));
        var row = mongo.findOne(profileQuery(tenant, subject), Document.class, PROFILES);
        return new Document("name", member.displayName()).append("email", member.email())
                .append("location", row == null ? "" : row.getString("location")).append("phone", row == null ? "" : row.getString("phone"));
    }
    public Document update(CandidateIdentity who, String name, String location, String phone) {
        profile(who);
        name = clean(name, 2, 200, "Full name"); location = clean(location, 2, 200, "Current location"); phone = phone(phone);
        if (!members.updateDisplayName(who.tenantId(), who.subject(), name)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        mongo.upsert(profileQuery(who.tenantId(), who.subject()), new Update().set("tenantId", who.tenantId()).set("subject", who.subject())
                .set("name", name).set("email", who.email()).set("location", location).set("phone", phone), PROFILES);
        return profile(who);
    }
    private Query profileQuery(String tenant, String subject) { return Query.query(Criteria.where("tenantId").is(tenant).and("subject").is(subject)); }
    private Organization active(String tenant) { return organizations.findById(tenant).filter(org -> org.status() == OrganizationStatus.ACTIVE)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Organisation is unavailable")); }
    static String clean(String value, int min, int max, String label) {
        if (value == null || value.trim().length() < min || value.trim().length() > max || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(label + " is required and must be at most " + max + " characters");
        return value.trim();
    }
    static String phone(String value) {
        value = clean(value, 7, 32, "Phone number"); int digits = value.replaceAll("\\D", "").length();
        if (!value.matches("[+0-9() .-]+") || digits < 7 || digits > 15) throw new IllegalArgumentException("Enter a phone number with 7–15 digits");
        return value;
    }
    private String token() { byte[] bytes = new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private String digest(String value) { try { var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret, "HmacSHA256")); return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8))); } catch (java.security.GeneralSecurityException impossible) { throw new IllegalStateException(impossible); } }
    private ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.BAD_REQUEST, "The code is invalid, expired, or has already been used. Request a new code if needed."); }
}
