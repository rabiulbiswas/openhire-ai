package com.openhire.integration.client.http;

import com.openhire.integration.domain.WebhookSecurity;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import javax.net.ssl.*;
import org.springframework.stereotype.Component;

/** TLS to the exact validated IP, retaining hostname certificate checks and SNI. Never follows redirects. */
@Component
public class PinnedWebhookSender {
    public int send(String endpoint, Map<String, String> headers, String body) throws java.io.IOException {
        URI uri = URI.create(endpoint);
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 443))
            throw new java.io.IOException("Invalid webhook destination");
        var addresses = InetAddress.getAllByName(uri.getHost());
        for (var address : addresses)
            if (!WebhookSecurity.publicAddress(address))
                throw new java.io.IOException("Non-public webhook destination");
        if (addresses.length == 0) throw new java.io.IOException("No destination address");
        byte[] content = body.getBytes(StandardCharsets.UTF_8);
        if (content.length > 65536) throw new java.io.IOException("Webhook payload too large");
        try (var socket = new Socket()) {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
            socket.connect(new InetSocketAddress(addresses[0], 443), 5000);
            socket.setSoTimeout(10000);
            try (var tls = (SSLSocket)
                    ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(socket, uri.getHost(), 443, true)) {
                var params = tls.getSSLParameters();
                params.setEndpointIdentificationAlgorithm("HTTPS");
                params.setServerNames(List.of(new SNIHostName(uri.getHost())));
                tls.setSSLParameters(params);
                tls.startHandshake();
                String encodedPath = URI.create(uri.toASCIIString()).getRawPath();
                String path = encodedPath.isEmpty() ? "/" : encodedPath;
                var request = new StringBuilder("POST ")
                        .append(path)
                        .append(" HTTP/1.1\r\nHost: ")
                        .append(uri.getHost())
                        .append("\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: ")
                        .append(content.length)
                        .append("\r\n");
                for (var header : headers.entrySet()) {
                    if (!header.getKey().matches("X-OpenHire-[A-Za-z-]+")
                            || !header.getValue().matches("[A-Za-z0-9=.,_-]+"))
                        throw new java.io.IOException("Invalid webhook header");
                    request.append(header.getKey())
                            .append(": ")
                            .append(header.getValue())
                            .append("\r\n");
                }
                var output = tls.getOutputStream();
                output.write(request.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII));
                output.write(content);
                output.flush();
                var input = tls.getInputStream();
                var line = new StringBuilder();
                for (int i = 0; i < 512; i++) {
                    long remaining = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                    if (remaining <= 0) throw new java.net.SocketTimeoutException("Webhook deadline exceeded");
                    tls.setSoTimeout((int) Math.min(remaining, 10000));
                    int next = input.read();
                    if (next == -1 || next == '\n') break;
                    line.append((char) next);
                }
                if (!line.toString().matches("HTTP/1\\.[01] [0-9]{3} .*\\r"))
                    throw new java.io.IOException("Invalid HTTP response");
                return Integer.parseInt(line.substring(9, 12));
            }
        }
    }
}
