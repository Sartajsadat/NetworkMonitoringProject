package gov.mot.netmon.agent.transport;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Collection;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import com.fasterxml.jackson.databind.JsonNode;

import gov.mot.netmon.agent.model.Json;

/**
 * HTTPS client for the platform. Outbound only; the agent opens no listening port.
 * A private CA certificate (PEM) can be configured for ministry-internal TLS.
 */
public class PlatformClient {

    /** Outcome of one call, classified so the caller knows whether to retry, drop or stop. */
    public enum Outcome { OK, RETRY_LATER, REJECTED, REVOKED, UNAUTHORIZED }

    public record Response(Outcome outcome, int status, JsonNode body, String errorCode, String message) {
    }

    private final URI base;
    private final HttpClient http;

    public PlatformClient(URI base, Path caCertificate) throws Exception {
        this.base = base;
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER);
        if (caCertificate != null) {
            b.sslContext(sslContextFor(caCertificate));
        }
        this.http = b.build();
    }

    public Response post(String path, Object body, String authorization) {
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder(base.resolve(path))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "netmon-agent")
                    .POST(body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofByteArray(Json.MAPPER.writeValueAsBytes(body)));
            if (authorization != null) {
                req.header("Authorization", authorization);
            }
            HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode json = null;
            try {
                json = res.body() == null || res.body().isBlank() ? null : Json.MAPPER.readTree(res.body());
            } catch (IOException e) {
                // non-JSON body (e.g. a proxy error page)
            }
            String code = json != null && json.hasNonNull("code") ? json.get("code").asText() : null;
            String message = json != null && json.hasNonNull("message") ? json.get("message").asText() : null;
            int s = res.statusCode();
            Outcome outcome;
            if (s >= 200 && s < 300) outcome = Outcome.OK;
            else if (s == 401 && "AGENT_REVOKED".equals(code)) outcome = Outcome.REVOKED;
            else if (s == 401 || s == 403) outcome = Outcome.UNAUTHORIZED;
            else if (s == 400 || s == 413 || s == 422) outcome = Outcome.REJECTED;
            else outcome = Outcome.RETRY_LATER; // 429, 5xx, proxies
            return new Response(outcome, s, json, code, message);
        } catch (IOException e) {
            return new Response(Outcome.RETRY_LATER, 0, null, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Response(Outcome.RETRY_LATER, 0, null, null, "interrupted");
        }
    }

    static SSLContext sslContextFor(Path pem) throws Exception {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        try (InputStream in = Files.newInputStream(pem)) {
            Collection<? extends Certificate> certs = CertificateFactory.getInstance("X.509").generateCertificates(in);
            int i = 0;
            for (Certificate c : certs) {
                ks.setCertificateEntry("ca-" + i++, c);
            }
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }
}
