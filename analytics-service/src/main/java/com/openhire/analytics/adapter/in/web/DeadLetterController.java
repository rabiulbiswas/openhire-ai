package com.openhire.analytics.adapter.in.web;

import com.openhire.analytics.application.DeadLetterService;
import com.openhire.analytics.application.DeadLetterViews.BatchResult;
import com.openhire.analytics.application.DeadLetterViews.DeadLetter;
import com.openhire.analytics.application.DeadLetterViews.DeadLetterDetail;
import com.openhire.analytics.application.DeadLetterViews.ReplayResult;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/dlq")
class DeadLetterController {
    record ReplayInput(String reason) {}

    record BatchInput(String reason, String topic, Integer limit, Boolean dryRun) {}

    private final DeadLetterService deadLetters;
    private final Viewers viewers;

    DeadLetterController(DeadLetterService deadLetters, Viewers viewers) {
        this.deadLetters = deadLetters;
        this.viewers = viewers;
    }

    @GetMapping
    List<DeadLetter> list(Authentication auth, @RequestParam(defaultValue = "50") int limit) {
        return deadLetters.list(viewers.operator(auth), limit);
    }

    @GetMapping("/{id}")
    DeadLetterDetail detail(Authentication auth, @PathVariable String id) {
        return deadLetters.detail(viewers.operator(auth), id);
    }

    @PostMapping("/{id}/replay")
    ReplayResult replay(
            Authentication auth,
            @PathVariable String id,
            @RequestParam(defaultValue = "false") boolean force,
            @RequestBody ReplayInput input) {
        return deadLetters.replay(viewers.operator(auth), id, input.reason(), force);
    }

    @PostMapping("/replay")
    BatchResult replayBatch(Authentication auth, @RequestBody BatchInput input) {
        return deadLetters.replayBatch(
                viewers.operator(auth),
                input.reason(),
                input.topic(),
                input.limit(),
                Boolean.TRUE.equals(input.dryRun()));
    }
}
