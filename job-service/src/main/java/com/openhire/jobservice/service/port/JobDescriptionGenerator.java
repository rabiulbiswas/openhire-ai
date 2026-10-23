package com.openhire.jobservice.service.port;

import com.openhire.jobservice.dto.request.AiJobDraftRequest;
import com.openhire.jobservice.dto.response.AiJobDraftResponse;

public interface JobDescriptionGenerator {
    AiJobDraftResponse generate(AiJobDraftRequest request);
}
