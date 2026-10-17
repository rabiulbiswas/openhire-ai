package com.openhire.aigateway.domain;

import java.util.Arrays;
import java.util.Optional;

/** The seven routable AI tasks. The key is the public name used in settings; the prompt key names the template family. */
public enum AiTask {
    RESUME_STRUCTURE("resume.structure", "resume.structure", true),
    MATCH_JUDGE("match.judge", "match.criterion.judge", true),
    EMBEDDINGS("embeddings", "embed", true),
    JD_DRAFT("jd.draft", "jd.draft", false),
    MESSAGE_DRAFT("message.draft", "message.draft", false),
    NL_FILTER("nl.filter", "nl.filter", true),
    FIT_EXPLAIN("fit.explain", "fit.explain", false);

    private final String key;
    private final String promptKey;
    private final boolean cacheable;

    AiTask(String key, String promptKey, boolean cacheable) {
        this.key = key;
        this.promptKey = promptKey;
        this.cacheable = cacheable;
    }

    public String key() {
        return key;
    }

    public String promptKey() {
        return promptKey;
    }

    /** Deterministic tasks (temperature 0) whose results are cached by content. */
    public boolean cacheable() {
        return cacheable;
    }

    public boolean chat() {
        return this != EMBEDDINGS;
    }

    public static Optional<AiTask> ofKey(String key) {
        return Arrays.stream(values()).filter(t -> t.key.equals(key)).findFirst();
    }
}
