package com.openhire.resumeprocessor.client.extraction;

import com.openhire.resumeprocessor.service.port.ResumeTextExtractor;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.*;
import org.apache.pdfbox.Loader;
import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.HttpHeaders;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.microsoft.OfficeParser;
import org.apache.tika.parser.microsoft.ooxml.OOXMLParser;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
import org.apache.tika.parser.ocr.TesseractOCRParser;
import org.apache.tika.parser.pdf.PDFParser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

/** In-process parsers, with native Tesseract used only for sparse PDF fallback. */
@Component
public class EmbeddedResumeTextExtractor implements ResumeTextExtractor {
    private final ThreadPoolExecutor parsers;
    private final int timeoutSeconds, maxPages, maxCharacters, ocrTimeoutSeconds;
    private final String language;

    public EmbeddedResumeTextExtractor(
            @Value("${openhire.resume.extraction.concurrency:1}") int concurrency,
            @Value("${openhire.resume.extraction.timeout-seconds:80}") int timeoutSeconds,
            @Value("${openhire.resume.extraction.max-pages:20}") int maxPages,
            @Value("${openhire.resume.extraction.max-characters:1000000}") int maxCharacters,
            @Value("${openhire.resume.extraction.ocr-timeout-seconds:30}") int ocrTimeoutSeconds,
            @Value("${openhire.resume.extraction.ocr-language:eng}") String language) {
        if (concurrency < 1
                || concurrency > 4
                || timeoutSeconds < 1
                || timeoutSeconds > 80
                || maxPages < 1
                || maxPages > 50
                || maxCharacters < 1
                || maxCharacters > 1000000
                || ocrTimeoutSeconds < 1
                || ocrTimeoutSeconds > 30
                || !language.matches("[a-z]{3}(\\+[a-z]{3})*")) {
            throw new IllegalArgumentException("Invalid resume extraction limits");
        }
        this.timeoutSeconds = timeoutSeconds;
        this.maxPages = maxPages;
        this.maxCharacters = maxCharacters;
        this.ocrTimeoutSeconds = ocrTimeoutSeconds;
        this.language = language;
        // Small bounded queue. A parser ignoring interruption still occupies its original thread.
        parsers = new ThreadPoolExecutor(
                concurrency,
                concurrency,
                0,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(concurrency),
                Thread.ofPlatform().daemon(true).name("resume-parser-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public String extract(byte[] bytes, String mime, boolean ocr) throws IOException {
        if (bytes.length == 0 || bytes.length > 10 * 1024 * 1024) throw new IOException("INVALID_RESUME_SIZE");
        Future<String> task;
        try {
            task = parsers.submit(() -> parse(bytes, mime, ocr));
        } catch (RejectedExecutionException busy) {
            throw new SocketTimeoutException("EXTRACTION_BUSY");
        }
        try {
            return task.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            task.cancel(true);
            throw new SocketTimeoutException("EXTRACTION_TIMEOUT");
        } catch (InterruptedException interrupted) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("EXTRACTION_INTERRUPTED", interrupted);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof IOException io) throw io;
            throw new IOException("EXTRACTION_FAILED", failure.getCause());
        }
    }

    private String parse(byte[] bytes, String mime, boolean ocr) throws IOException {
        Parser parser =
                switch (mime) {
                    case "application/pdf" -> new PDFParser();
                    case "application/msword" -> new OfficeParser();
                    case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> new OOXMLParser();
                    default -> throw new IOException("UNSUPPORTED_RESUME_TYPE");
                };
        if (ocr && !"application/pdf".equals(mime)) throw new IOException("OCR_REQUIRES_PDF");
        if ("application/pdf".equals(mime)) {
            // Reject rather than silently truncate an over-limit resume.
            try (var pdf = Loader.loadPDF(bytes)) {
                if (pdf.getNumberOfPages() > maxPages) throw new IOException("PDF_PAGE_LIMIT_EXCEEDED");
                if (ocr) {
                    for (var page : pdf.getPages()) {
                        double scale = 150.0 / 72;
                        var box = page.getCropBox();
                        double pixels = Math.ceil(box.getWidth() * scale) * Math.ceil(box.getHeight() * scale);
                        if (!Double.isFinite(pixels) || pixels <= 0 || pixels > 10_000_000)
                            throw new IOException("OCR_PAGE_IMAGE_LIMIT_EXCEEDED");
                    }
                }
            }
        }
        var context = new ParseContext();
        context.set(Parser.class, parser);
        // Resume attachments/embedded files are not recursively parsed or OCR'd.
        context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
            public boolean shouldParseEmbedded(Metadata metadata) {
                return false;
            }

            public void parseEmbedded(
                    java.io.InputStream stream, ContentHandler handler, Metadata metadata, boolean outputHtml) {}
        });
        var pdfConfig = new PDFParserConfig();
        pdfConfig.setOcrStrategy(ocr ? "ocr_only" : "no_ocr");
        pdfConfig.setOcrDPI(150);
        pdfConfig.setExtractInlineImages(false);
        pdfConfig.setExtractAcroFormContent(false);
        pdfConfig.setCatchIntermediateIOExceptions(false);
        context.set(PDFParserConfig.class, pdfConfig);
        var ocrConfig = new TesseractOCRConfig();
        ocrConfig.setSkipOcr(!ocr);
        ocrConfig.setLanguage(language);
        ocrConfig.setTimeoutSeconds(ocrTimeoutSeconds);
        ocrConfig.setEnableImagePreprocessing(false);
        context.set(TesseractOCRConfig.class, ocrConfig);
        try {
            if (ocr) {
                var ocrParser = new TesseractOCRParser();
                if (!ocrParser.hasTesseract()) throw new IOException("OCR_ENGINE_UNAVAILABLE");
                // PDFParser delegates rendered image/ocr-* pages through the context parser.
                context.set(Parser.class, ocrParser);
            }
            var metadata = new Metadata();
            metadata.set(HttpHeaders.CONTENT_TYPE, mime);
            var handler = new BodyContentHandler(maxCharacters);
            try (var input = new ByteArrayInputStream(bytes)) {
                parser.parse(input, handler, metadata, context);
            }
            if (Thread.currentThread().isInterrupted()) throw new IOException("EXTRACTION_INTERRUPTED");
            return handler.toString();
        } catch (SAXException | TikaException failure) {
            // Never persist partial/truncated extraction as a successful resume.
            throw new IOException("EXTRACTION_FAILED", failure);
        }
    }

    @PreDestroy
    public void shutdown() {
        parsers.shutdownNow();
    }
}
