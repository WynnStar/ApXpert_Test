package tw.holdings.quote.client;

import java.io.IOException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tw.holdings.quote.Market;
import tw.holdings.quote.QuoteResult;

@Component
class TwseQuoteClient implements QuoteSource {
    private final OfficialQuoteHttp http;
    private final String endpoint;

    TwseQuoteClient(OfficialQuoteHttp http, @Value("${quotes.twse-url}") String endpoint) {
        this.http = http;
        this.endpoint = endpoint;
    }

    @Override
    public Market market() {
        return Market.TWSE;
    }

    @Override
    public QuoteResult fetch(String code, YearMonth month, LocalDate today)
            throws IOException, InterruptedException {
        String date = month.atDay(1).format(DateTimeFormatter.BASIC_ISO_DATE);
        JsonNode body = http.fetch(market(), endpoint + "?date=" + date + "&stockNo=" + code + "&response=json");
        String status = body.path("stat").asText();
        if (status.equals("很抱歉，沒有符合條件的資料!") || status.equals("很抱歉，沒有符合條件的資料！")) {
            return QuoteResult.notFound();
        }
        if (!status.equalsIgnoreCase("OK")) {
            throw new IOException("Unexpected TWSE response status");
        }

        var title = Pattern.compile("\\s" + Pattern.quote(code) + "\\s+(.+?)\\s+各日成交資訊")
                .matcher(body.path("title").asText());
        if (!title.find()) {
            throw new IOException("Missing TWSE stock name");
        }
        var price = OfficialQuoteData.latest(body.path("fields"), body.path("data"), today);
        return QuoteResult.found(title.group(1).strip(), market(),
                price == null ? null : price.close(), price == null ? null : price.date());
    }
}
