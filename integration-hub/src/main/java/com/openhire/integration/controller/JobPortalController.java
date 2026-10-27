package com.openhire.integration.controller;

import com.openhire.integration.service.JobPortals;
import com.openhire.support.Access;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/job-portals")
class JobPortalController {
    private final JobPortals portals;
    private final Access access;

    JobPortalController(JobPortals portals, Access access) {
        this.portals = portals;
        this.access = access;
    }

    private String tenant(Authentication auth, boolean admin) {
        boolean allowed = access.role(auth, "ORG_ADMIN")
                || (!admin
                        && (access.role(auth, "RECRUITER")
                                || access.role(auth, "HR_ADMIN")
                                || access.role(auth, "HIRING_MANAGER")));
        if (!allowed) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return access.tenant(auth);
    }

    record Configuration(String secretRef, boolean enabled, long revision) {}

    record Desired(boolean enabled, long revision) {}

    @GetMapping
    List<JobPortals.Connection> connections(Authentication auth) {
        return portals.connections(tenant(auth, false));
    }

    @PutMapping("/{provider}")
    JobPortals.Connection configure(
            Authentication auth, @PathVariable String provider, @RequestBody Configuration input) {
        return portals.configure(
                tenant(auth, true), access.actor(auth), provider, input.secretRef(), input.enabled(), input.revision());
    }

    @GetMapping("/jobs/{job}")
    List<JobPortals.Posting> postings(Authentication auth, @PathVariable String job) {
        return portals.postings(tenant(auth, false), job);
    }

    @PutMapping("/jobs/{job}/{provider}")
    JobPortals.Posting desire(
            Authentication auth, @PathVariable String job, @PathVariable String provider, @RequestBody Desired input) {
        if (!access.role(auth, "ORG_ADMIN") && !access.role(auth, "RECRUITER") && !access.role(auth, "HR_ADMIN"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return portals.desire(
                tenant(auth, false), access.actor(auth), job, provider, input.enabled(), input.revision());
    }
}
