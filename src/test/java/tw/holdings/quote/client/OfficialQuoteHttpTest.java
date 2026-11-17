package tw.holdings.quote.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tw.holdings.quote.Market;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OfficialQuoteHttpTest {
    private static final Instant NOW = Instant.parse("2026-11-17T05:35:00Z");

    @Test
    void forbiddenRateLimitServerAndMalformedJsonResponsesCoolOnlyTheirSource() throws Exception {
        for (int status : new int[] {403, 429, 503, 200}) {
            var calls = new AtomicInteger();
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/failure", exchange -> {
                calls.incrementAndGet();
                if (status == 429) {
                    exchange.getResponseHeaders().add("Retry-After", "3600");
                }
                byte[] body = (status == 200 ? "invalid-json" : "{}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.start();
            try {
                var limiter = new OfficialQuoteRateLimit(Duration.ZERO, Duration.ZERO);
                var http = new OfficialQuoteHttp(new ObjectMapper(), limiter, Clock.fixed(NOW, ZoneOffset.UTC));
                String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/failure";
                assertThrows(IOException.class, () -> http.fetchDocument(Market.TWSE, url));
                assertThrows(IOException.class, () -> http.fetchDocument(Market.TWSE, url));
                assertEquals(1, calls.get());
                Instant expires = NOW.plusSeconds(status == 429 ? 3600 : 1800);
                assertThrows(IOException.class, () -> limiter.gate(Market.TWSE).beforeRequest(expires.minusSeconds(1)));
                assertDoesNotThrow(() -> limiter.gate(Market.TWSE).beforeRequest(expires));
                assertDoesNotThrow(() -> limiter.gate(Market.TPEX).beforeRequest(NOW));
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void connectionFailureCoolsSourceButValidNotFoundJsonDoesNot() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/unknown", exchange -> {
            byte[] body = "{\"stat\":\"no data\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        int port = server.getAddress().getPort();
        var limiter = new OfficialQuoteRateLimit(Duration.ZERO, Duration.ZERO);
        var http = new OfficialQuoteHttp(new ObjectMapper(), limiter, Clock.fixed(NOW, ZoneOffset.UTC));
        try {
            http.fetchDocument(Market.TWSE, "http://127.0.0.1:" + port + "/unknown");
            assertDoesNotThrow(() -> limiter.gate(Market.TWSE).beforeRequest(NOW));
        } finally {
            server.stop(0);
        }
        assertThrows(IOException.class, () -> http.fetchDocument(Market.TWSE, "http://127.0.0.1:" + port + "/unknown"));
        assertThrows(IOException.class, () -> limiter.gate(Market.TWSE).beforeRequest(NOW.plusSeconds(1799)));
        assertDoesNotThrow(() -> limiter.gate(Market.TPEX).beforeRequest(NOW));
    }
}
