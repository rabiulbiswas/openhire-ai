package com.openhire.aiworkers.integrity.controller;

import com.openhire.aiworkers.integrity.domain.Signal;
import com.openhire.aiworkers.integrity.service.IntegrityService;
import com.openhire.support.Access;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/integrity")
class IntegrityController {
    private final IntegrityService integrity;
    private final Access access;

    IntegrityController(IntegrityService integrity, Access access) {
        this.integrity = integrity;
        this.access = access;
    }

    record DismissInput(String reason) {}

    @GetMapping("/signals")
    List<Map<String, Object>> list(Authentication auth, @RequestParam String applicationId) {
        access.requireStaff(auth);
        return integrity.signals(access.tenant(auth), applicationId).stream()
                .map(IntegrityController::view)
                .toList();
    }

    @PostMapping("/signals/{id}/dismiss")
    Map<String, Object> dismiss(Authentication auth, @PathVariable String id, @RequestBody DismissInput body) {
        access.requireStaff(auth);
        return view(integrity.dismiss(access.tenant(auth), id, access.actor(auth), body.reason()));
    }

    static Map<String, Object> view(Signal signal) {
        var view = new LinkedHashMap<String, Object>();
        view.put("id", signal.id());
        view.put("applicationId", signal.applicationId());
        view.put("candidateId", signal.candidateId());
        view.put("type", signal.type());
        view.put("severity", signal.severity());
        view.put("confidence", signal.confidence());
        view.put("reason", signal.evidence());
        view.put("status", signal.status());
        view.put("createdAt", signal.createdAt().toString());
        if (Signal.DISMISSED.equals(signal.status())) {
            view.put("dismissedBy", signal.dismissedBy());
            view.put("dismissReason", signal.dismissReason());
            view.put(
                    "dismissedAt",
                    signal.dismissedAt() == null ? null : signal.dismissedAt().toString());
        }
        return view;
    }
}
