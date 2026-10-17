package com.openhire.identity.adapter.out.mongo;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import com.openhire.identity.application.port.IdentityRepositories.ApiKeys;
import com.openhire.identity.application.port.IdentityRepositories.Invitations;
import com.openhire.identity.application.port.IdentityRepositories.Members;
import com.openhire.identity.application.port.IdentityRepositories.Organizations;
import com.openhire.identity.application.port.IdentityRepositories.Roles;
import com.openhire.identity.application.port.IdentityRepositories.SetupProgresses;
import com.openhire.identity.application.port.IdentityRepositories.TenantSettings;
import com.openhire.identity.domain.IdentityModels.ApiKey;
import com.openhire.identity.domain.IdentityModels.BreakGlassRequest;
import com.openhire.identity.domain.IdentityModels.Invitation;
import com.openhire.identity.domain.IdentityModels.Member;
import com.openhire.identity.domain.IdentityModels.Organization;
import com.openhire.identity.domain.IdentityModels.Role;
import com.openhire.identity.domain.IdentityModels.SetupProgress;
import com.openhire.identity.domain.IdentityModels.TenantSecuritySettings;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
public class MongoIdentityRepository
        implements Organizations, Members, Roles, Invitations, ApiKeys, SetupProgresses, TenantSettings {
    private static final String ORGANIZATIONS = "organizations";
    private static final String MEMBERS = "users";
    private static final String ROLES = "roles";
    private static final String INVITATIONS = "invites";
    private static final String API_KEYS = "api_keys";
    private static final String SETUP = "setup_progress";
    private static final String SETTINGS = "tenant_security_settings";
    private static final String BREAK_GLASS = "break_glass_requests";
    private final MongoTemplate mongo;

    public MongoIdentityRepository(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Organization save(Organization value) {
        return mongo.save(value, ORGANIZATIONS);
    }

    @Override
    public Optional<Organization> findById(String id) {
        return Optional.ofNullable(mongo.findById(id, Organization.class, ORGANIZATIONS));
    }

    @Override
    public Optional<Organization> findBySlug(String slug) {
        return Optional.ofNullable(
                mongo.findOne(Query.query(where("slug").is(slug)), Organization.class, ORGANIZATIONS));
    }

    @Override
    public List<Organization> findAll() {
        return mongo.findAll(Organization.class, ORGANIZATIONS);
    }

    @Override
    public Member save(Member value) {
        return mongo.save(value, MEMBERS);
    }

    @Override
    public Optional<Member> findMemberById(String organizationId, String id) {
        return findOne(
                MEMBERS,
                Member.class,
                tenantQuery(organizationId).addCriteria(where("_id").is(id)));
    }

    @Override
    public Optional<Member> findByEmail(String organizationId, String email) {
        return findOne(
                MEMBERS,
                Member.class,
                tenantQuery(organizationId).addCriteria(where("email").is(email)));
    }

    @Override
    public List<Member> findMembers(String organizationId) {
        return mongo.find(tenantQuery(organizationId), Member.class, MEMBERS);
    }

    @Override
    public Role save(Role value) {
        return mongo.save(value, ROLES);
    }

    @Override
    public Optional<Role> findRoleById(String organizationId, String id) {
        return findOne(
                ROLES,
                Role.class,
                tenantQuery(organizationId).addCriteria(where("_id").is(id)));
    }

    @Override
    public Optional<Role> findRoleByName(String organizationId, String name) {
        return findOne(
                ROLES,
                Role.class,
                tenantQuery(organizationId).addCriteria(where("name").is(name)));
    }

    @Override
    public List<Role> findRoles(String organizationId) {
        return mongo.find(tenantQuery(organizationId), Role.class, ROLES);
    }

    @Override
    public Invitation save(Invitation value) {
        return mongo.save(value, INVITATIONS);
    }

    @Override
    public Optional<Invitation> findInvitationById(String organizationId, String id) {
        return findOne(
                INVITATIONS,
                Invitation.class,
                tenantQuery(organizationId).addCriteria(where("_id").is(id)));
    }

    @Override
    public Optional<Invitation> findByTokenHash(String tokenHash) {
        return findOne(
                INVITATIONS, Invitation.class, Query.query(where("tokenHash").is(tokenHash)));
    }

    @Override
    public List<Invitation> findInvitations(String organizationId) {
        return mongo.find(tenantQuery(organizationId), Invitation.class, INVITATIONS);
    }

    @Override
    public ApiKey save(ApiKey value) {
        return mongo.save(value, API_KEYS);
    }

    @Override
    public Optional<ApiKey> findApiKeyById(String organizationId, String id) {
        return findOne(
                API_KEYS,
                ApiKey.class,
                tenantQuery(organizationId).addCriteria(where("_id").is(id)));
    }

    @Override
    public Optional<ApiKey> findApiKeyByPrefix(String prefix) {
        return findOne(API_KEYS, ApiKey.class, Query.query(where("prefix").is(prefix)));
    }

    @Override
    public List<ApiKey> findApiKeys(String organizationId) {
        return mongo.find(tenantQuery(organizationId), ApiKey.class, API_KEYS);
    }

    @Override
    public SetupProgress save(SetupProgress value) {
        mongo.upsert(
                Query.query(where("organizationId").is(value.organizationId())),
                new Update()
                        .set("organizationId", value.organizationId())
                        .set("completedSteps", value.completedSteps())
                        .set("updatedAt", value.updatedAt()),
                SETUP);
        return value;
    }

    @Override
    public Optional<SetupProgress> find(String organizationId) {
        return findOne(
                SETUP, SetupProgress.class, Query.query(where("organizationId").is(organizationId)));
    }

    @Override
    public TenantSecuritySettings save(TenantSecuritySettings value) {
        mongo.upsert(
                Query.query(where("organizationId").is(value.organizationId())),
                new Update()
                        .set("organizationId", value.organizationId())
                        .set("authPolicy", value.authPolicy())
                        .set("sso", value.sso())
                        .set("quota", value.quota())
                        .set("updatedAt", value.updatedAt()),
                SETTINGS);
        return value;
    }

    @Override
    public Optional<TenantSecuritySettings> findSettings(String organizationId) {
        return findOne(
                SETTINGS,
                TenantSecuritySettings.class,
                Query.query(where("organizationId").is(organizationId)));
    }

    @Override
    public BreakGlassRequest saveBreakGlass(BreakGlassRequest value) {
        return mongo.save(value, BREAK_GLASS);
    }

    @Override
    public List<BreakGlassRequest> findBreakGlassRequests(String organizationId) {
        return mongo.find(tenantQuery(organizationId), BreakGlassRequest.class, BREAK_GLASS);
    }

    private Query tenantQuery(String organizationId) {
        return Query.query(where("organizationId").is(organizationId));
    }

    private <T> Optional<T> findOne(String collection, Class<T> type, Query query) {
        return Optional.ofNullable(mongo.findOne(query, type, collection));
    }
}
