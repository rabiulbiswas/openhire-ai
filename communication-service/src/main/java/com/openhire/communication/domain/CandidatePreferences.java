package com.openhire.communication.domain;

import com.openhire.communication.domain.CommunicationTypes.Channel;
import com.openhire.communication.domain.CommunicationTypes.QuietHours;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** How a candidate wants to be contacted. WhatsApp and SMS need a phone number on the application. */
@Document("candidate_preferences")
public class CandidatePreferences {
    @Id
    private String id;

    private String tenantId;
    private String candidateId;
    private Channel preferredChannel;
    private QuietHours quietHours;
    private boolean marketingOptOut;
    private Instant updatedAt;

    protected CandidatePreferences() {}

    public static String idFor(String tenantId, String candidateId) {
        return tenantId + ":" + candidateId;
    }

    public static CandidatePreferences defaults(String tenantId, String candidateId) {
        var value = new CandidatePreferences();
        value.id = idFor(tenantId, candidateId);
        value.tenantId = tenantId;
        value.candidateId = candidateId;
        value.preferredChannel = Channel.EMAIL;
        value.quietHours = QuietHours.off();
        return value;
    }

    public void update(Channel channel, QuietHours quiet, boolean optOut, Instant now) {
        preferredChannel = channel == null ? Channel.EMAIL : channel;
        quietHours = quiet == null ? QuietHours.off() : quiet;
        marketingOptOut = optOut;
        updatedAt = now;
    }

    /** Uses the preferred channel only when the contact detail it needs exists. */
    public Channel channelFor(boolean hasPhone) {
        if (preferredChannel != Channel.EMAIL && !hasPhone) return Channel.EMAIL;
        return preferredChannel;
    }

    public String id() {
        return id;
    }

    public Channel preferredChannel() {
        return preferredChannel;
    }

    public QuietHours quietHours() {
        return quietHours;
    }

    public boolean marketingOptOut() {
        return marketingOptOut;
    }
}
