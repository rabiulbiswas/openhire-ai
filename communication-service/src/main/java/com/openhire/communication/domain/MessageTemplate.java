package com.openhire.communication.domain;

import com.openhire.communication.domain.CommunicationTypes.Channel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/** A tenant's versioned message template. Editing keeps every earlier version for audit. */
@Document("templates")
public class MessageTemplate {
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*([a-zA-Z]+(?:\\.[a-zA-Z]+)*)\\s*}}");
    public static final List<String> VARIABLES = List.of(
            "candidate.firstName",
            "candidate.name",
            "job.title",
            "round.name",
            "round.start",
            "round.timezone",
            "meeting.url",
            "booking.url",
            "company.name",
            "recruiter.name",
            "offer.url",
            "offer.expiryDate",
            "offer.joiningDate");

    @Id
    private String id;

    @Version
    private Long revision;

    private String tenantId;
    private String key;
    private String name;
    private List<Channel> channels;
    private String subject;
    private String body;
    private int version;
    private List<Revision> history;
    private Instant updatedAt;
    private String updatedBy;

    public record Revision(int version, String subject, String body, Instant at, String by) {}

    public record Rendered(String subject, String body) {}

    protected MessageTemplate() {}

    public static String idFor(String tenantId, String key) {
        return tenantId + ":" + key;
    }

    public static MessageTemplate create(
            String tenantId,
            String key,
            String name,
            List<Channel> channels,
            String subject,
            String body,
            String by,
            Instant now) {
        var template = new MessageTemplate();
        template.id = idFor(tenantId, key);
        template.tenantId = tenantId;
        template.key = key;
        template.history = new ArrayList<>();
        template.version = 0;
        template.name = name;
        template.revise(channels, subject, body, by, now);
        return template;
    }

    public void revise(List<Channel> nextChannels, String nextSubject, String nextBody, String by, Instant now) {
        if (nextBody == null || nextBody.isBlank())
            throw new IllegalArgumentException("A template needs a message body");
        if (nextChannels == null || nextChannels.isEmpty())
            throw new IllegalArgumentException("Choose at least one channel");
        var unknown = unknownVariables(nextSubject + " " + nextBody);
        if (!unknown.isEmpty()) throw new IllegalArgumentException("Unknown variables: " + String.join(", ", unknown));
        if (version > 0) history.add(new Revision(version, subject, body, updatedAt, updatedBy));
        channels = List.copyOf(nextChannels);
        subject = nextSubject == null ? "" : nextSubject.trim();
        body = nextBody.trim();
        version++;
        updatedAt = now;
        updatedBy = by;
    }

    public Rendered render(Map<String, String> values) {
        return new Rendered(fill(subject, values), fill(body, values));
    }

    static String fill(String text, Map<String, String> values) {
        Matcher matcher = VARIABLE.matcher(text == null ? "" : text);
        var result = new StringBuilder();
        while (matcher.find()) {
            var value = values.getOrDefault(matcher.group(1), "");
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    static List<String> unknownVariables(String text) {
        var matcher = VARIABLE.matcher(text == null ? "" : text);
        var unknown = new ArrayList<String>();
        while (matcher.find()) if (!VARIABLES.contains(matcher.group(1))) unknown.add(matcher.group(1));
        return unknown;
    }

    public String id() {
        return id;
    }

    public String tenantId() {
        return tenantId;
    }

    public String key() {
        return key;
    }

    public String name() {
        return name;
    }

    public List<Channel> channels() {
        return channels;
    }

    public String subject() {
        return subject;
    }

    public String body() {
        return body;
    }

    public int version() {
        return version;
    }

    public List<Revision> history() {
        return List.copyOf(history);
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
