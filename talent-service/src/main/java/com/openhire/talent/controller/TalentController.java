package com.openhire.talent.controller;

import com.openhire.talent.domain.TalentTypes.Filters;
import com.openhire.talent.dto.TalentViews.PoolHealth;
import com.openhire.talent.dto.TalentViews.SearchResult;
import com.openhire.talent.service.InvitationService;
import com.openhire.talent.service.RediscoveryService;
import com.openhire.talent.service.SearchService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/talent")
class TalentController {
    private final RediscoveryService rediscovery;
    private final SearchService search;
    private final InvitationService invitations;
    private final Viewers viewers;

    TalentController(
            RediscoveryService rediscovery, SearchService search, InvitationService invitations, Viewers viewers) {
        this.rediscovery = rediscovery;
        this.search = search;
        this.invitations = invitations;
        this.viewers = viewers;
    }

    record NlInput(String query) {}

    record SearchInput(Filters filters, String semanticQuery) {}

    record InviteInput(String jobId) {}

    @GetMapping("/rediscovery")
    SearchResult rediscovery(
            Authentication auth,
            @RequestParam(required = false) String jobId,
            @RequestParam(defaultValue = "false") boolean refresh) {
        return rediscovery.forJob(viewers.staffTenant(auth), jobId, refresh);
    }

    @PostMapping("/search/nl")
    SearchResult naturalLanguage(Authentication auth, @RequestBody NlInput input) {
        return search.naturalLanguage(viewers.staffTenant(auth), input.query());
    }

    @PostMapping("/search")
    SearchResult search(Authentication auth, @RequestBody SearchInput input) {
        return search.search(viewers.staffTenant(auth), input.filters(), input.semanticQuery());
    }

    @GetMapping("/pool-health")
    PoolHealth poolHealth(Authentication auth) {
        return search.poolHealth(viewers.staffTenant(auth));
    }

    @PostMapping("/{candidateId}/invite")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void invite(
            Authentication auth, @PathVariable String candidateId, @RequestBody(required = false) InviteInput input) {
        var tenant = viewers.staffTenant(auth);
        invitations.invite(tenant, viewers.viewer(auth), candidateId, input == null ? null : input.jobId());
    }
}
