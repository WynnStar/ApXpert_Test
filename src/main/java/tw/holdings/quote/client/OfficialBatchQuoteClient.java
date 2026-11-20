package tw.holdings.quote.client;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
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
            @Value("${quotes.twse-batch-url:https://www.twse.com.tw/rwd/zh/afterTrading/MI_INDEX}") String twseUrl,
            @Value("${quotes.tpex-batch-url:https://www.tpex.org.tw/www/zh-tw/afterTrading/otc}") String tpexUrl) {
        this.http = http;
        this.twseUrl = twseUrl;
        this.tpexUrl = tpexUrl;
    }

    public Snapshot fetch(Market market, LocalDate taipeiDate) throws IOException, InterruptedException {
        boolean twse = market == Market.TWSE;
        String date = taipeiDate.format(twse ? DateTimeFormatter.BASIC_ISO_DATE
                : DateTimeFormatter.ofPattern("uuuu/MM/dd"));
        String url = UriComponentsBuilder.fromUriString(twse ? twseUrl : tpexUrl)
                .replaceQueryParam("date", date)
                .replaceQueryParam("type", twse ? "ALLBUT0999" : "EW")
                .replaceQueryParam("response", "json")
                .build().encode().toUriString();
        return BatchQuoteParser.parse(http.fetchDocument(market, url), market, taipeiDate);
    }
}
