package com.openhire.resumeprocessor;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
import org.junit.jupiter.api.Test;

class ResumeValidationTest {
    private byte[] zip(String... entries) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (String entry : entries) {
                zip.putNextEntry(new ZipEntry(entry));
                zip.write("sample".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    @Test
    void checksContentsRatherThanTrustingFileExtension() throws Exception {
        assertThat(ResumePipeline.validate("%PDF-1.7\nsample".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("application/pdf");
        assertThatThrownBy(() -> ResumePipeline.validate("fake.pdf executable".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validatesDocxStructureAndRejectsZipSlipAndMacros() throws Exception {
        assertThat(ResumePipeline.validate(zip("[Content_Types].xml", "word/document.xml")))
                .contains("wordprocessingml");
        for (String unsafe : new String[] {"../escape", "/absolute", "word\\escape", "word/vbaProject.bin"}) {
            byte[] bytes = zip("[Content_Types].xml", "word/document.xml", unsafe);
            assertThatThrownBy(() -> ResumePipeline.validate(bytes)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> ResumePipeline.validate(zip("unrelated.txt")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void boundsUploadedSize() {
        assertThatThrownBy(() -> ResumePipeline.validate(new byte[0])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ResumePipeline.validate(new byte[ResumePipeline.MAX_BYTES + 1]))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
