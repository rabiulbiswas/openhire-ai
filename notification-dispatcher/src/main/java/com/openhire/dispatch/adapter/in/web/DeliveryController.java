package com.openhire.dispatch.adapter.in.web;

import com.openhire.dispatch.application.DispatchService;
import com.openhire.dispatch.application.DispatchService.DeliveryView;
import com.openhire.dispatch.domain.Delivery;
import com.openhire.support.Access;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Failed and deferred delivery visibility, plus an operator retry. */
@RestController
@RequestMapping("/api/v1/deliveries")
class DeliveryController {
    private final DispatchService dispatch;
    private final Access access;

    DeliveryController(DispatchService dispatch, Access access) {
        this.dispatch = dispatch;
        this.access = access;
    }

    @GetMapping
    Map<String, List<DeliveryView>> list(
            Authentication auth,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit) {
        access.requireStaff(auth);
        Delivery.Status filter = null;
        if (status != null && !status.isBlank()) {
            try {
                filter = Delivery.Status.valueOf(status);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("status must be PENDING, DEFERRED, SENT or FAILED");
            }
        }
        return Map.of("data", dispatch.list(access.tenant(auth), filter, limit));
    }

    @GetMapping("/{messageId}")
    DeliveryView get(Authentication auth, @PathVariable String messageId) {
        access.requireStaff(auth);
        return dispatch.get(access.tenant(auth), messageId);
    }

    @PostMapping("/{messageId}/retry")
    DeliveryView retry(Authentication auth, @PathVariable String messageId) {
        access.requireStaff(auth);
        return dispatch.retry(access.tenant(auth), messageId);
    }
}
