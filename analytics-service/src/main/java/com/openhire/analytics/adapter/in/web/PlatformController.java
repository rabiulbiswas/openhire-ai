package com.openhire.analytics.adapter.in.web;

import com.openhire.analytics.application.PlatformQueryService;
import com.openhire.analytics.application.PlatformViews.ConsumerLag;
import com.openhire.analytics.application.PlatformViews.PlatformService;
import com.openhire.analytics.application.PlatformViews.Slo;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/platform")
class PlatformController {
    private final PlatformQueryService platform;
    private final Viewers viewers;
    private final com.openhire.analytics.application.MonitoredSlos slos;

    PlatformController(
            PlatformQueryService platform, Viewers viewers, com.openhire.analytics.application.MonitoredSlos slos) {
        this.platform = platform;
        this.viewers = viewers;
        this.slos = slos;
    }

    @GetMapping("/services")
    List<PlatformService> services(Authentication auth) {
        viewers.requireSuperAdmin(auth);
        return platform.services();
    }

    @GetMapping("/slo")
    List<Slo> slo(Authentication auth) {
        return slos.read(viewers.sloTenant(auth));
    }

    @GetMapping("/consumers")
    List<ConsumerLag> consumers(Authentication auth) {
        viewers.requireSuperAdmin(auth);
        return platform.consumers();
    }
}
