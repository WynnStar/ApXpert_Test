package tw.holdings.quote.client;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tw.holdings.quote.Market;
import tw.holdings.quote.QuoteResult;

@Component
public class OfficialBatchQuoteClient {
    public record Snapshot(LocalDate priceDate, Map<String, QuoteResult> quotes) {
        public Snapshot {
            quotes = Map.copyOf(quotes);
        }
    }

    private final OfficialQuoteHttp http;
    private final String twseUrl;
    private final String tpexUrl;

    OfficialBatchQuoteClient(OfficialQuoteHttp http,
            @Value("${quotes.twse-batch-url:https://openapi.twse.com.tw/v1/exchangeReport/STOCK_DAY_ALL}") String twseUrl,
            @Value("${quotes.tpex-batch-url:https://www.tpex.org.tw/openapi/v1/tpex_mainboard_daily_close_quotes}") String tpexUrl) {
        this.http = http;
        this.twseUrl = twseUrl;
        this.tpexUrl = tpexUrl;
    }

    public Snapshot fetch(Market market, LocalDate today) throws IOException, InterruptedException {
        return parse(http.fetchDocument(market, market == Market.TWSE ? twseUrl : tpexUrl), market, today);
    }

    static Snapshot parse(JsonNode rows, Market market, LocalDate today) throws IOException {
        if (!rows.isArray() || rows.isEmpty()) {
            throw new IOException("Expected non-empty official batch quotes");
        }
        boolean twse = market == Market.TWSE;
        Map<String, QuoteResult> quotes = new LinkedHashMap<>();
        LocalDate latest = null;
        try {
            for (JsonNode row : rows) {
                String code = row.path(twse ? "Code" : "SecuritiesCompanyCode").asText("").strip();
                if (!code.matches("[0-9]{4}[0-9A-Z]{0,2}")) {
                    continue;
                }
                String name = row.path(twse ? "Name" : "CompanyName").asText("").strip();
                if (name.isEmpty()) {
                    throw new IOException("Missing batch stock name");
                }
                LocalDate date = date(row.path("Date").asText("").strip());
                if (date.isAfter(today)) {
                    continue;
                }
                String rawClose = row.path(twse ? "ClosingPrice" : "Close").asText("").replace(",", "").strip();
                String volume = row.path(twse ? "TradeVolume" : "TradingShares").asText("").replace(",", "").strip();
                boolean traded = volume.matches("[0-9]+") && new BigDecimal(volume).signum() > 0;
                BigDecimal close = rawClose.isEmpty() || rawClose.equals("--") || rawClose.equals("---")
                        ? null : new BigDecimal(rawClose);
                boolean priced = traded && close != null && close.signum() > 0;
                QuoteResult quote = QuoteResult.found(name, market, priced ? close : null, priced ? date : null);
                QuoteResult previous = quotes.get(code);
                if (previous == null || previous.priceDate() == null
                        || quote.priceDate() != null && quote.priceDate().isAfter(previous.priceDate())) {
                    quotes.put(code, quote);
                }
                if (latest == null || date.isAfter(latest)) {
                    latest = date;
                }
            }
        } catch (NumberFormatException | DateTimeException invalid) {
            throw new IOException("Invalid official batch date or number", invalid);
        }
        if (quotes.isEmpty() || latest == null) {
            throw new IOException("Official batch contains no usable stock identities");
        }
        return new Snapshot(latest, quotes);
    }

    private static LocalDate date(String text) {
        if (text.matches("[0-9]{7}")) {
            return LocalDate.of(Integer.parseInt(text.substring(0, 3)) + 1911,
                    Integer.parseInt(text.substring(3, 5)), Integer.parseInt(text.substring(5, 7)));
        }
        if (text.matches("[0-9]{8}")) {
            return LocalDate.parse(text, DateTimeFormatter.BASIC_ISO_DATE);
        }
        return LocalDate.parse(text);
    }
}
