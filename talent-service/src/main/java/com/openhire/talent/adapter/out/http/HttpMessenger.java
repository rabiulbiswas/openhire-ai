package com.openhire.talent.adapter.out.http;

import com.openhire.support.Access;
import com.openhire.support.ServiceClient;
import com.openhire.talent.application.port.TalentPorts.Messenger;
import com.openhire.talent.application.port.TalentPorts.TemplateSend;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Sends through communication-service so the candidate's channel, quiet hours and opt-outs apply. */
@Component
class HttpMessenger implements Messenger {
    private final ServiceClient communication;

    HttpMessenger(Access access, @Value("${openhire.talent.communication-url}") String url) {
        communication = new ServiceClient(url, access.secret());
    }

    @Override
    public TemplateSend sendTemplate(
            String tenantId, String applicationId, String templateKey, Map<String, String> variables, String actor) {
        var body = new LinkedHashMap<String, Object>();
        body.put("tenantId", tenantId);
        body.put("applicationId", applicationId);
        body.put("templateKey", templateKey);
        body.put("variables", variables);
        body.put("actor", actor);
        var response = communication.post("/api/v1/internal/messages/template", body);
        return new TemplateSend(
                response != null && Boolean.TRUE.equals(response.get("sent")),
                response == null ? null : response.getString("reason"));
    }
}
