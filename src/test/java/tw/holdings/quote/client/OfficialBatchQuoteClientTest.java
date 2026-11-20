package tw.holdings.quote.client;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tw.holdings.quote.Market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class OfficialBatchQuoteClientTest {
    @ParameterizedTest
    @EnumSource(Market.class)
    void requestsConfiguredEndpointWithTaipeiDateAndMarketType(Market market) throws Exception {
        boolean twse = market == Market.TWSE;
        byte[] fixture;
        try (var input = getClass().getResourceAsStream(
                twse ? "/quotes/twse-mi-index.json" : "/quotes/tpex-otc.json")) {
            assertNotNull(input);
            fixture = input.readAllBytes();
        }
        var requested = new AtomicReference<URI>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mirror", exchange -> {
            requested.set(exchange.getRequestURI());
            exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(200, fixture.length);
            try (var output = exchange.getResponseBody()) {
                output.write(fixture);
            }
        });
        server.start();
        try {
            var clock = Clock.fixed(Instant.parse("2026-11-19T16:00:00Z"), ZoneOffset.UTC);
            LocalDate taipeiDate = clock.instant().atZone(java.time.ZoneId.of("Asia/Taipei")).toLocalDate();
            var http = new OfficialQuoteHttp(new ObjectMapper(),
                    new OfficialQuoteRateLimit(Duration.ZERO, Duration.ZERO), clock);
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/mirror";
            String oldQuery = "?date=19990101&type=wrong&response=csv&marker=kept";
            var client = new OfficialBatchQuoteClient(http, base + "/MI_INDEX" + oldQuery,
                    base + "/otc" + oldQuery);

            var result = client.fetch(market, taipeiDate);

            assertEquals(LocalDate.of(2026, 11, 20), result.priceDate());
            assertEquals(twse ? "/mirror/MI_INDEX" : "/mirror/otc", requested.get().getPath());
            Map<String, String> query = Arrays.stream(requested.get().getRawQuery().split("&"))
                    .map(pair -> pair.split("=", 2))
                    .collect(Collectors.toMap(pair -> pair[0],
                            pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
            assertEquals(twse ? "20261120" : "2026/11/20", query.get("date"));
            assertEquals(twse ? "ALLBUT0999" : "EW", query.get("type"));
            assertEquals("json", query.get("response"));
            assertEquals("kept", query.get("marker"));
            assertEquals(market, result.quotes().values().iterator().next().market());
        } finally {
            server.stop(0);
        }
    }
}
