package com.openhire.identity.service.port;

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

public final class IdentityRepositories {
    private IdentityRepositories() {}

    public interface Organizations {
        Organization save(Organization value);

        Optional<Organization> findById(String id);

        Optional<Organization> findBySlug(String slug);

        List<Organization> findAll();
    }

    public interface Members {
        Member save(Member value);

        boolean updateDisplayName(String tenantId, String id, String displayName);

        Optional<Member> findMemberById(String organizationId, String id);

        Optional<Member> findByEmail(String organizationId, String email);

        List<Member> findMembers(String organizationId);
    }

    public interface Roles {
        Role save(Role value);

        Optional<Role> findRoleById(String organizationId, String id);

        Optional<Role> findRoleByName(String organizationId, String name);

        List<Role> findRoles(String organizationId);
    }

    public interface Invitations {
        Invitation save(Invitation value);

        Optional<Invitation> findInvitationById(String organizationId, String id);

        Optional<Invitation> findByTokenHash(String tokenHash);

        List<Invitation> findInvitations(String organizationId);
    }

    public interface ApiKeys {
        ApiKey save(ApiKey value);

        Optional<ApiKey> findApiKeyById(String organizationId, String id);

        Optional<ApiKey> findApiKeyByPrefix(String prefix);

        List<ApiKey> findApiKeys(String organizationId);
    }

    public interface SetupProgresses {
        SetupProgress save(SetupProgress value);

        Optional<SetupProgress> find(String organizationId);
    }

    public interface TenantSettings {
        TenantSecuritySettings save(TenantSecuritySettings value);

        Optional<TenantSecuritySettings> findSettings(String organizationId);

        BreakGlassRequest saveBreakGlass(BreakGlassRequest value);

        List<BreakGlassRequest> findBreakGlassRequests(String organizationId);
    }
}
