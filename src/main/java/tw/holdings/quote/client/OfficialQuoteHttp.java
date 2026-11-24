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
        return fetchDocument(market,url,false);
    }

    JsonNode fetchOptionalDocument(Market market,String url) throws IOException,InterruptedException {
        return fetchDocument(market,url,true);
    }

    private static class OptionalDocumentException extends IOException {
        private static final long serialVersionUID=1L;
        OptionalDocumentException(String message) { super(message); }
    }

    private JsonNode fetchDocument(Market market,String url,boolean optional) throws IOException,InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(4))
                .GET()
                .build();
        var gate = rateLimit.gate(market);
        synchronized (gate) {
            try {
                gate.beforeRequest(clock.instant());
            } catch (IOException unavailable) {
                throw new OfficialSourceUnavailableException(unavailable,gate.availableAt());
            }
            String retryAfter = "";
            try {
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                retryAfter = response.headers().firstValue("Retry-After").orElse("");
                if (optional && (response.statusCode()==400 || response.statusCode()==404))
                    throw new OptionalDocumentException("Calendar endpoint HTTP "+response.statusCode());
                if (response.statusCode() != 200) {
                    throw new IOException("HTTP " + response.statusCode());
                }
                JsonNode payload = mapper.readTree(response.body());
                if (payload == null) {
                    throw new IOException("Empty official response");
                }
                return payload;
            } catch (OptionalDocumentException metadataUnavailable) {
                throw metadataUnavailable;
            } catch (IOException exception) {
                gate.pause(clock.instant(), retryAfter);
                throw new OfficialSourceUnavailableException(exception,gate.availableAt());
            } finally {
                gate.afterRequest();
            }
        }
    }
}
