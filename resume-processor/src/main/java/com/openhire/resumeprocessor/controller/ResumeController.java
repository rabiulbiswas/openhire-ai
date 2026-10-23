package com.openhire.resumeprocessor.controller;

import com.openhire.resumeprocessor.service.ResumePipeline;
import com.openhire.support.Access;
import org.bson.Document;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class ResumeController {
    private final ResumePipeline pipeline;
    private final Access access;

    public ResumeController(ResumePipeline pipeline, Access access) {
        this.pipeline = pipeline;
        this.access = access;
    }

    @PostMapping("/internal/resumes")
    public Document upload(
            @RequestHeader("X-Internal-Service-Secret") String secret,
            @RequestParam String tenantId,
            @RequestParam String applicationId,
            @RequestPart MultipartFile file)
            throws Exception {
        access.internal(secret);
        return pipeline.upload(tenantId, applicationId, file.getOriginalFilename(), file.getBytes());
    }

    @GetMapping("/internal/resumes/{id}")
    public Document result(
            @RequestHeader("X-Internal-Service-Secret") String secret,
            @RequestParam String tenantId,
            @PathVariable String id) {
        access.internal(secret);
        return pipeline.result(tenantId, id);
    }

    @PostMapping("/internal/resumes/{id}/retry")
    public Document retry(
            @RequestHeader("X-Internal-Service-Secret") String secret,
            @RequestParam String tenantId,
            @PathVariable String id) {
        access.internal(secret);
        pipeline.retry(tenantId, id);
        return pipeline.result(tenantId, id);
    }

    @GetMapping("/resumes/{id}/download")
    public ResponseEntity<byte[]> download(Authentication auth, @PathVariable String id) throws Exception {
        access.requireStaff(auth);
        var tenant = access.tenant(auth);
        var row = pipeline.result(tenant, id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(row.getString("mime")))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(row.getString("name"), java.nio.charset.StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(pipeline.download(tenant, id));
    }
}
