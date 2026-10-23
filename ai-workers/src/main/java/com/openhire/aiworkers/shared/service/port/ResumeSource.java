package com.openhire.aiworkers.shared.service.port;

/** The resume processor's extracted text for one resume. */
public interface ResumeSource {
    Resume fetch(String tenantId, String resumeId);

    record Resume(String status, String text, String name) {
        public boolean ready() {
            return "DONE".equals(status) && text != null && !text.isBlank();
        }
    }
}
