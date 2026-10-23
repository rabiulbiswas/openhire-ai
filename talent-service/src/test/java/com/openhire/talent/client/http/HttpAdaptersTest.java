package com.openhire.talent.client.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openhire.support.Access;
import com.openhire.talent.domain.TalentTypes.Chunk;
import com.openhire.talent.service.port.TalentPorts.AiUnavailableException;
import com.openhire.talent.service.port.TalentPorts.ExplainRequest;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HttpAdaptersTest {
    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> tenant = new AtomicReference<>();

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private HttpAiGateway gateway(int status, String response) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/internal/ai/", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            tenant.set(exchange.getRequestHeaders().getFirst("X-Tenant-Id"));
            var bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return new HttpAiGateway(
                new Access("local", "demo-org", "test-secret"),
                "http://127.0.0.1:" + server.getAddress().getPort(),
                2000);
    }

    @Test
    void embedStructureAndNlFilterResponsesAreParsed() throws Exception {
        var embed = gateway(200, "{\"vectors\":[[0.5,1.0,-2]],\"dimensions\":3,\"provenance\":{}}");
        assertThat(embed.embed("t1", "hello")).containsExactly(0.5f, 1.0f, -2f);
        assertThat(tenant.get()).isEqualTo("t1");
        assertThat(body.get()).contains("\"inputs\":[\"hello\"]");
        server.stop(0);

        var structured = gateway(
                200,
                "{\"headline\":\"Engineer\",\"skills\":[\"Java\"],\"roles\":[{\"title\":\"Staff Engineer\"}],\"totalYearsExperience\":9.5}");
        var resume = structured.structureResume("t1", "text");
        assertThat(resume.skills()).containsExactly("Java");
        assertThat(resume.titles()).containsExactly("Staff Engineer");
        assertThat(resume.totalYearsExperience()).isEqualTo(9.5);
        server.stop(0);

        var nl = gateway(
                200,
                "{\"filters\":{\"locations\":[\"Pune\"],\"skills\":[\"Kafka\"],\"minYears\":5,\"openToRelocate\":true},"
                        + "\"semanticQuery\":\"kafka\",\"chips\":[\"Skill: Kafka\"]}");
        var filter = nl.nlFilter("t1", "kafka in pune");
        assertThat(filter.filters().locations()).containsExactly("Pune");
        assertThat(filter.filters().minYears()).isEqualTo(5);
        assertThat(filter.filters().openToRelocate()).isTrue();
        assertThat(filter.chips()).containsExactly("Skill: Kafka");
    }

    @Test
    void explainFitSendsHistoricalAndCurrentAsSeparateLists() throws Exception {
        var client = gateway(
                200, "{\"whyFitNow\":\"CURRENT: fits\",\"historicalEvidence\":[\"HISTORICAL: a\",\"HISTORICAL: b\"]}");

        var result = client.explainFit(
                "t1",
                new ExplainRequest(
                        "Backend Engineer",
                        "summary",
                        List.of("Java"),
                        List.of(new Chunk("history-1", "Applied for X")),
                        List.of(new Chunk("c1", "Java at scale"))));

        assertThat(result.whyFitNow()).isEqualTo("CURRENT: fits");
        assertThat(result.historicalEvidence()).isEqualTo("HISTORICAL: a HISTORICAL: b");
        assertThat(body.get())
                .contains("\"historical\":[{\"chunkId\":\"history-1\",\"text\":\"Applied for X\"}]")
                .contains("\"current\":[{\"chunkId\":\"c1\",\"text\":\"Java at scale\"}]");
    }

    @Test
    void budgetOutageAndUnreachableAllBecomeUnavailable() throws Exception {
        assertThatThrownBy(() -> gateway(503, "{\"code\":\"AI_UNAVAILABLE\"}").embed("t1", "x"))
                .isInstanceOf(AiUnavailableException.class);
        server.stop(0);
        assertThatThrownBy(
                        () -> gateway(402, "{\"code\":\"AI_BUDGET_EXHAUSTED\"}").nlFilter("t1", "x"))
                .isInstanceOf(AiUnavailableException.class);
        server.stop(0);
        assertThatThrownBy(() -> gateway(200, "{\"vectors\":[]}").embed("t1", "x"))
                .isInstanceOf(AiUnavailableException.class);
        var down = new HttpAiGateway(new Access("local", "demo-org", "test-secret"), "http://127.0.0.1:1", 500);
        assertThatThrownBy(() -> down.embed("t1", "x")).isInstanceOf(AiUnavailableException.class);
    }

    @Test
    void jobsAndApplicationsAreReadFromPlainJsonMaps() {
        var version = new LinkedHashMap<String, Object>();
        version.put("description", "Build services");
        version.put("mustHaveSkills", List.of("Java", "Kafka"));
        version.put("rubric", List.of(Map.of("label", "Java"), Map.of("label", "System design")));
        var job = new LinkedHashMap<String, Object>();
        job.put("title", "Backend Engineer");
        job.put("status", "OPEN");
        job.put("currentVersion", version);

        var info = HttpDirectories.jobInfo("job-1", job);

        assertThat(info.title()).isEqualTo("Backend Engineer");
        assertThat(info.mustHaveSkills()).containsExactly("Java", "Kafka");
        assertThat(info.criteria()).containsExactly("Java", "Kafka", "System design");

        var application = HttpDirectories.applicationInfo(
                "app-1",
                Map.of(
                        "candidateId",
                        "c1",
                        "candidateName",
                        "Priya",
                        "jobId",
                        "j",
                        "jobTitle",
                        "T",
                        "stage",
                        "APPLIED",
                        "appliedAt",
                        "2026-10-01T00:00:00Z",
                        "resumeId",
                        ""));
        assertThat(application.candidateId()).isEqualTo("c1");
        assertThat(application.resumeId()).isNull();
        assertThat(application.appliedAt()).isNotNull();
    }
}
