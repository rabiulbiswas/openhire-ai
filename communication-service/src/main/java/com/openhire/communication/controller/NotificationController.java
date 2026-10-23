package com.openhire.communication.controller;

import com.openhire.communication.domain.CommunicationTypes.DeliveryChannel;
import com.openhire.communication.domain.CommunicationTypes.NotificationEventKey;
import com.openhire.communication.domain.CommunicationTypes.QuietHours;
import com.openhire.communication.domain.NotificationPreferences;
import com.openhire.communication.dto.CommunicationViews.NotificationView;
import com.openhire.communication.dto.CommunicationViews.Page;
import com.openhire.communication.dto.CommunicationViews.PreferencesView;
import com.openhire.communication.service.CommunicationService;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class NotificationController {
    private final CommunicationService communication;
    private final StaffViewers viewers;

    NotificationController(CommunicationService communication, StaffViewers viewers) {
        this.communication = communication;
        this.viewers = viewers;
    }

    record ReadInput(Boolean read) {}

    record QuietInput(boolean enabled, String from, String to) {}

    record PreferencesInput(Map<String, Map<String, Boolean>> matrix, QuietInput quietHours, String emailDigest) {}

    @GetMapping("/notifications")
    Page<NotificationView> list(
            Authentication auth,
            @RequestParam(defaultValue = "all") String filter,
            @RequestParam(defaultValue = "50") int limit) {
        return communication.notifications(viewers.tenant(auth), viewers.viewer(auth), filter, limit);
    }

    @GetMapping("/notifications/unread-count")
    Map<String, Long> unread(Authentication auth) {
        return Map.of("count", communication.unreadCount(viewers.tenant(auth), viewers.viewer(auth)));
    }

    @PatchMapping("/notifications/{id}")
    Map<String, String> read(Authentication auth, @PathVariable String id, @RequestBody ReadInput input) {
        if (!Boolean.TRUE.equals(input.read())) throw new IllegalArgumentException("Only marking as read is supported");
        communication.markRead(viewers.tenant(auth), id, viewers.viewer(auth));
        return Map.of("status", "READ");
    }

    @PostMapping("/notifications/read-all")
    Map<String, String> readAll(Authentication auth) {
        communication.markAllRead(viewers.tenant(auth), viewers.viewer(auth));
        return Map.of("status", "READ");
    }

    @GetMapping("/me/notification-preferences")
    PreferencesView preferences(Authentication auth) {
        return communication.preferences(viewers.tenant(auth), viewers.viewer(auth));
    }

    @PutMapping("/me/notification-preferences")
    PreferencesView update(Authentication auth, @RequestBody PreferencesInput input) {
        var matrix = new EnumMap<NotificationEventKey, Map<DeliveryChannel, Boolean>>(NotificationEventKey.class);
        if (input.matrix() != null)
            input.matrix().forEach((key, row) -> {
                var channels = new EnumMap<DeliveryChannel, Boolean>(DeliveryChannel.class);
                row.forEach((channel, enabled) -> channels.put(enumValue(DeliveryChannel.class, channel), enabled));
                matrix.put(enumValue(NotificationEventKey.class, key), channels);
            });
        var quiet = input.quietHours() == null
                ? null
                : new QuietHours(
                        input.quietHours().enabled(),
                        input.quietHours().from(),
                        input.quietHours().to(),
                        null);
        var digest = input.emailDigest() == null
                ? null
                : enumValue(NotificationPreferences.Digest.class, input.emailDigest());
        return communication.updatePreferences(viewers.tenant(auth), viewers.viewer(auth), matrix, quiet, digest);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Unknown value " + value);
        }
    }
}
