package com.openhire.resumeprocessor.service.port;

import java.io.IOException;

public interface ResumeTextExtractor {
    String extract(byte[] bytes, String mime, boolean ocr) throws IOException;
}
