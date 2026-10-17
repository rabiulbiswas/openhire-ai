package com.openhire.applicationservice.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.openhire.applicationservice.application.port.ConsentSource;
import com.openhire.applicationservice.application.port.ConsentSource.Consent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ConsentTextsTest {
    private final AtomicLong now = new AtomicLong(1_000);
    private final List<String> calls = new ArrayList<>();
    private Optional<Consent> published = Optional.of(new Consent("v2", "Published text"));
    private final ConsentSource source = tenant -> {
        calls.add(tenant);
        return published;
    };
    private final ConsentTexts texts = new ConsentTexts(source, "privacy-v1", "demo-org", now::get);

    @Test
    void servesPublishedConsentAndCachesItForSixtySeconds() {
        assertThat(texts.active("t1")).isEqualTo(new Consent("v2", "Published text"));
        published = Optional.of(new Consent("v3", "Newer"));
        now.addAndGet(59_000);
        assertThat(texts.version("t1")).isEqualTo("v2");
        assertThat(calls).hasSize(1);
        now.addAndGet(1_001);
        assertThat(texts.version("t1")).isEqualTo("v3");
        assertThat(calls).hasSize(2);
    }

    @Test
    void cacheIsPerTenantAndBlankTenantUsesTheDefault() {
        texts.active("t1");
        texts.active("t2");
        texts.active(null);
        texts.active("demo-org");
        assertThat(calls).containsExactly("t1", "t2", "demo-org");
    }

    @Test
    void fallsBackToBuiltInTextWhenSourceHasNothing() {
        published = Optional.empty();
        var active = texts.active("t1");
        assertThat(active.version()).isEqualTo("privacy-v1");
        assertThat(active.text()).isEqualTo(ConsentTexts.BUILT_IN_TEXT);
    }

    @Test
    void validatedVersionIsAlwaysTheServedOne() {
        published = Optional.empty();
        String served = texts.active("t1").version();
        published = Optional.of(new Consent("v9", "Later"));
        assertThat(texts.version("t1")).isEqualTo(served);
    }
}
