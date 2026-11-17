package tw.holdings.quote;

import java.io.IOException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class TpexQuoteClient implements QuoteSource {
    private static final DateTimeFormatter REQUEST_DATE =
            DateTimeFormatter.ofPattern("uuuu/MM/dd", Locale.ROOT);
    private final OfficialQuoteHttp http;
    private final String endpoint;

    TpexQuoteClient(OfficialQuoteHttp http, @Value("${quotes.tpex-url}") String endpoint) {
        this.http = http;
        this.endpoint = endpoint;
    }

    @Override
    public Market market() {
        return Market.TPEX;
    }

    @Override
    public QuoteResult fetch(String code, YearMonth month, LocalDate today)
            throws IOException, InterruptedException {
        String date = month.atDay(1).format(REQUEST_DATE);
        JsonNode body = http.fetch(market(), endpoint + "?code=" + code + "&date=" + date + "&response=json");
        if (!body.path("stat").asText().equalsIgnoreCase("ok")) {
            throw new IOException("Unexpected TPEx response status");
        }

        JsonNode table = body.path("tables").path(0);
        JsonNode rows = table.path("data");
        String name = body.path("name").asText("").strip();
        if (!rows.isArray()) {
            throw new IOException("Missing TPEx daily trade rows");
        }
        if (name.isEmpty() && rows.isEmpty() && table.path("totalCount").asInt(-1) == 0) {
            return QuoteResult.notFound();
        }
        if (name.isEmpty() || !code.equals(body.path("code").asText())) {
            throw new IOException("Missing or mismatched TPEx stock identity");
        }
        var price = OfficialQuoteData.latest(table.path("fields"), rows, today);
        return QuoteResult.found(name, market(), price);
    }
}
