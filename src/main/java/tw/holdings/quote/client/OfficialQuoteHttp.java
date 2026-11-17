package tw.holdings.quote.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import tw.holdings.quote.Market;

@Component
class OfficialQuoteHttp {
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final ObjectMapper mapper;

    private final OfficialQuoteRateLimit rateLimit;
    private final Clock clock;

    OfficialQuoteHttp(ObjectMapper mapper, OfficialQuoteRateLimit rateLimit, Clock clock) {
        this.mapper = mapper;
        this.rateLimit = rateLimit;
        this.clock = clock;
    }

    JsonNode fetch(Market market, String url) throws IOException, InterruptedException {
        JsonNode payload = fetchDocument(market, url);
        if (!payload.isObject()) {
            throw new IOException("Expected an official JSON object");
        }
        return payload;
    }

    JsonNode fetchDocument(Market market, String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(4))
                .GET()
                .build();
        HttpResponse<String> response;
        var gate = rateLimit.gate(market);
        synchronized (gate) {
            gate.beforeRequest(clock.instant());
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) {
                gate.pause(clock.instant(), response.headers().firstValue("Retry-After").orElse(""));
            }
        }
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }
        JsonNode payload = mapper.readTree(response.body());
        if (payload == null) {
            throw new IOException("Empty official response");
        }
        return payload;
    }
}
