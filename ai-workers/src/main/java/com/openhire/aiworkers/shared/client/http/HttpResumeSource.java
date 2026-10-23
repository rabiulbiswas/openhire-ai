package com.openhire.aiworkers.shared.client.http;

import com.openhire.aiworkers.shared.service.port.ResumeSource;
import com.openhire.support.Access;
import com.openhire.support.Records;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class HttpResumeSource implements ResumeSource {
    private final InternalClient resumes;

    HttpResumeSource(Access access, @Value("${openhire.ai.resume-url}") String resumeUrl) {
        resumes = new InternalClient(resumeUrl, access.secret(), 15000);
    }

    @Override
    public Resume fetch(String tenantId, String resumeId) {
        var row = resumes.get("/api/v1/internal/resumes/{id}?tenantId={tenant}", resumeId, tenantId);
        if (row == null) throw new IllegalStateException("Resume " + resumeId + " was not found");
        return new Resume(Records.text(row, "status"), Records.text(row, "text"), Records.text(row, "name"));
    }
}
