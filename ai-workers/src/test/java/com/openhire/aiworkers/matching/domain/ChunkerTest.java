package com.openhire.aiworkers.matching.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ChunkerTest {
    private static String words(String prefix, int count) {
        return IntStream.range(0, count).mapToObj(i -> prefix + i).collect(Collectors.joining(" "));
    }

    @Test
    void labelsSectionsByHeadingAndKeepsShortSectionsWhole() {
        var text = "Backend engineer.\n\nEXPERIENCE\nBuilt Kafka pipelines.\n\nSkills:\nJava, SQL\n\nEducation\nBTech";
        var chunks = new Chunker().chunk("res-1", text);

        assertThat(chunks).extracting(Chunk::section).containsExactly("SUMMARY", "EXPERIENCE", "SKILLS", "EDUCATION");
        assertThat(chunks.get(1).text()).isEqualTo("Built Kafka pipelines.");
    }

    @Test
    void longSectionsBecomeOverlappingWindowsOfAboutFourHundredWords() {
        var chunks = new Chunker().chunk("res-1", "EXPERIENCE\n" + words("w", 1200));

        assertThat(chunks).hasSizeGreaterThan(2);
        for (var chunk : chunks) {
            int count = chunk.text().split("\\s+").length;
            assertThat(count).isBetween(300, 500);
            assertThat(chunk.section()).isEqualTo("EXPERIENCE");
        }
        var first = chunks.get(0).text().split("\\s+");
        var second = chunks.get(1).text().split("\\s+");
        assertThat(second[0]).isEqualTo(first[first.length - 60]);
        assertThat(chunks.get(chunks.size() - 1).text()).endsWith("w1199");
    }

    @Test
    void chunkIdsAreStableAndDependOnTheResume() {
        var text = "EXPERIENCE\n" + words("w", 900);
        var first = new Chunker().chunk("res-1", text);
        var again = new Chunker().chunk("res-1", text);
        var other = new Chunker().chunk("res-2", text);

        assertThat(first)
                .extracting(Chunk::id)
                .containsExactlyElementsOf(again.stream().map(Chunk::id).toList());
        assertThat(first.get(0).id())
                .startsWith("chk_")
                .isNotEqualTo(other.get(0).id());
    }

    @Test
    void chunkTextIsASliceOfTheOriginalSoQuotesStillMatch() {
        var text = "EXPERIENCE\nLed migration\nto Kafka  with    care";
        assertThat(new Chunker().chunk("r", text).get(0).text()).isEqualTo("Led migration\nto Kafka  with    care");
    }

    @Test
    void emptyTextHasNoChunks() {
        assertThat(new Chunker().chunk("r", " \n ")).isEmpty();
    }
}
