package com.openhire.jobservice.application.port;

import com.openhire.jobservice.api.AiJobDraftRequest;
import com.openhire.jobservice.api.AiJobDraftResponse;

public interface JobDescriptionGenerator {
    AiJobDraftResponse generate(AiJobDraftRequest request);
}
