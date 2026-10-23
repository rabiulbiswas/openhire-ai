package com.openhire.integration.controller;

import com.openhire.integration.repository.mongo.McpConfirmations;
import com.openhire.integration.service.IntegrationService;
import com.openhire.platform.audit.AuditRecord;
import com.openhire.platform.audit.AuditRecorder;
import com.openhire.support.Access;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** Stateless JSON response transport. No arbitrary HTTP tool, no credential exposure, no implicit writes. */
@RestController
class McpController {
    private final McpAccess policy;
    private final Access access;
    private final IntegrationService service;
    private final McpConfirmations confirmations;
    private final AuditRecorder audit;
    private final ObjectMapper json;
    private final Set<String> origins;

    McpController(
            McpAccess policy,
            Access access,
            IntegrationService service,
            McpConfirmations confirmations,
            AuditRecorder audit,
            ObjectMapper json,
            @Value("${openhire.integration.mcp-origins:http://localhost:5173}") String origins) {
        this.policy = policy;
        this.access = access;
        this.service = service;
        this.confirmations = confirmations;
        this.audit = audit;
        this.json = json;
        this.origins = Set.copyOf(Arrays.asList(origins.split(",")));
    }

    @PostMapping(value = "/api/v1/mcp", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> call(
            Authentication auth,
            @RequestHeader(value = "Origin", required = false) String origin,
            @RequestBody Map<String, Object> request) {
        if (origin != null && !origins.contains(origin))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Origin not allowed");
        String tenant = policy.require(auth, "integrations.read");
        Object id = request.get("id");
        String method = String.valueOf(request.get("method"));
        if (!"2.0".equals(request.get("jsonrpc"))) return rpcError(id, -32600, "Invalid JSON-RPC request");
        if (id == null && method.equals("notifications/initialized"))
            return ResponseEntity.accepted().build();
        if (!(id instanceof String) && !(id instanceof Number)) return rpcError(null, -32600, "Request ID required");
        if (request.containsKey("params") && !(request.get("params") instanceof Map<?, ?>))
            return rpcError(id, -32602, "Parameters must be an object");
        Map<?, ?> params = request.get("params") instanceof Map<?, ?> p ? p : Map.of();
        Object result;
        switch (method) {
            case "initialize" ->
                result = Map.of(
                        "protocolVersion",
                        "2025-06-18",
                        "capabilities",
                        Map.of("tools", Map.of("listChanged", false)),
                        "serverInfo",
                        Map.of("name", "openhire-integrations", "version", "1.0.0"));
            case "ping" -> result = Map.of();
            case "tools/list" ->
                result = Map.of(
                        "tools",
                        List.of(
                                tool(
                                        "connectors.list",
                                        "List this organization's connector configuration",
                                        Map.of("after", Map.of("type", "string", "maxLength", 200)),
                                        List.of(),
                                        true),
                                tool(
                                        "connectors.disable",
                                        "Disable a connector only after explicit approval in OpenHire",
                                        Map.of(
                                                "connectorId",
                                                Map.of("type", "string"),
                                                "confirmationId",
                                                Map.of("type", "string")),
                                        List.of("connectorId"),
                                        false)));
            case "tools/call" -> {
                String name = String.valueOf(params.get("name"));
                if (params.containsKey("arguments") && !(params.get("arguments") instanceof Map<?, ?>))
                    return rpcError(id, -32602, "Arguments must be an object");
                Map<?, ?> args = params.get("arguments") instanceof Map<?, ?> a ? a : Map.of();
                if (name.equals("connectors.list")) {
                    if (!Set.of("after").containsAll(args.keySet())
                            || (args.containsKey("after")
                                    && (!(args.get("after") instanceof String cursor) || cursor.length() > 200)))
                        return rpcError(id, -32602, "Only an optional after cursor is accepted");
                    var page = service.page(tenant, "CONNECTOR", (String) args.get("after"), 100);
                    var listing = new LinkedHashMap<String, Object>();
                    listing.put(
                            "connectors",
                            page.stream()
                                    .map(c -> Map.of(
                                            "id",
                                            c.id(),
                                            "name",
                                            c.name(),
                                            "provider",
                                            c.provider(),
                                            "enabled",
                                            c.enabled(),
                                            "version",
                                            c.version()))
                                    .toList());
                    if (page.size() == 100)
                        listing.put("nextCursor", page.getLast().id());
                    result = content(listing, false);
                } else if (name.equals("connectors.disable")) {
                    policy.require(auth, "integrations.write");
                    if (!(args.get("connectorId") instanceof String connector)
                            || connector.length() > 200
                            || connector.isBlank()
                            || !Set.of("connectorId", "confirmationId").containsAll(args.keySet()))
                        return rpcError(id, -32602, "Valid connectorId required");
                    try {
                        if (!(args.get("confirmationId") instanceof String confirmation)) {
                            String pending = confirmations.prepare(tenant, access.actor(auth), connector);
                            result = content(
                                    Map.of(
                                            "status",
                                            "HUMAN_APPROVAL_REQUIRED",
                                            "confirmationId",
                                            pending,
                                            "reviewPath",
                                            "/admin/connectors"),
                                    false);
                        } else {
                            confirmations.execute(tenant, access.actor(auth), connector, confirmation);
                            result = content(Map.of("status", "DISABLED"), false);
                        }
                    } catch (ResponseStatusException | IntegrationService.Conflict failure) {
                        result = content(
                                Map.of(
                                        "status",
                                        "NOT_EXECUTED",
                                        "message",
                                        "Approval missing, expired, consumed, or connector changed. Review in OpenHire."),
                                true);
                    }
                } else return rpcError(id, -32602, "Unknown tool");
                audit.record(AuditRecord.of(tenant, "MCP_TOOL_CALLED")
                        .user(access.actor(auth), null)
                        .resource("MCP_TOOL", name)
                        .build());
            }
            default -> {
                return rpcError(id, -32601, "Method not found");
            }
        }
        return ResponseEntity.ok(Map.of("jsonrpc", "2.0", "id", id, "result", result));
    }

    private Map<String, Object> content(Map<String, ?> result, boolean error) {
        return Map.of(
                "content",
                List.of(Map.of("type", "text", "text", json.writeValueAsString(result))),
                "structuredContent",
                result,
                "isError",
                error);
    }

    private static Map<String, Object> tool(
            String name, String description, Map<String, ?> properties, List<String> required, boolean read) {
        return Map.of(
                "name",
                name,
                "description",
                description,
                "inputSchema",
                Map.of("type", "object", "properties", properties, "required", required, "additionalProperties", false),
                "annotations",
                Map.of("readOnlyHint", read, "destructiveHint", !read));
    }

    private static ResponseEntity<?> rpcError(Object id, int code, String message) {
        var body = new LinkedHashMap<String, Object>();
        body.put("jsonrpc", "2.0");
        body.put("id", id);
        body.put("error", Map.of("code", code, "message", message));
        return ResponseEntity.ok(body);
    }

    @GetMapping("/api/v1/connectors/mcp-confirmations")
    List<org.bson.Document> pending(Authentication auth) {
        return confirmations.pending(policy.browser(auth), access.actor(auth));
    }

    record Decision(boolean approve) {}

    @PostMapping("/api/v1/connectors/mcp-confirmations/{id}/decision")
    Map<String, String> decide(Authentication auth, @PathVariable String id, @RequestBody Decision decision) {
        String tenant = policy.browser(auth);
        confirmations.decide(tenant, access.actor(auth), id, decision.approve());
        audit.record(AuditRecord.of(tenant, decision.approve() ? "MCP_WRITE_APPROVED" : "MCP_WRITE_REJECTED")
                .user(access.actor(auth), null)
                .resource("MCP_CONFIRMATION", id)
                .build());
        return Map.of("status", decision.approve() ? "APPROVED" : "REJECTED");
    }
}
