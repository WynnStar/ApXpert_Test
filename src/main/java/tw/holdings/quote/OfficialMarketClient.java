package tw.holdings.quote;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

@Component
class OfficialMarketClient {
    private static final String TWSE = "https://www.twse.com.tw/rwd/zh";
    private static final String TWSE_OPEN = "https://openapi.twse.com.tw/v1";
    private static final String TPEX = "https://www.tpex.org.tw/www/zh-tw";
    private static final String TPEX_OPEN = "https://www.tpex.org.tw/openapi/v1";

    record Listing(String code, Market market, LocalDate date, String issuer, String name) {}

    private final OfficialQuoteHttp http;

    OfficialMarketClient(OfficialQuoteHttp http) {
        this.http = http;
    }

    List<MarketEvent> delistings(Market market, int year) throws IOException, InterruptedException {
        boolean twse = market == Market.TWSE;
        JsonNode body = http.fetch(market, twse ? TWSE + "/company/suspendListing?response=json"
                : TPEX + "/company/deListed?code=&date=" + year + "&reason=-1&response=json");
        JsonNode table = twse ? body : body.path("tables").path(0);
        return events(market, array(table.path("data")), twse ? 2 : 0, 1, twse ? 0 : 2,
                MarketEvent.Kind.DELISTED);
    }

    List<MarketEvent> transfersToTwse(int year) throws IOException, InterruptedException {
        var body = http.fetch(Market.TPEX,
                TPEX + "/company/deListed?code=&date=" + year + "&reason=2&response=json");
        return events(Market.TWSE, array(body.path("tables").path(0).path("data")),
                0, 1, 2, MarketEvent.Kind.RELISTED);
    }

    List<Listing> listings(Market market) throws IOException, InterruptedException {
        boolean twse = market == Market.TWSE;
        var rows = array(http.fetchDocument(market, twse ? TWSE_OPEN + "/opendata/t187ap03_L"
                : TPEX_OPEN + "/mopsfin_t187ap03_O"));
        if (rows.isEmpty()) {
            throw new IOException("Official listing roster is empty");
        }
        var listings = new ArrayList<Listing>();
        for (JsonNode row : rows) {
            String code = row.path(twse ? "公司代號" : "SecuritiesCompanyCode").asText().strip();
            String issuer = row.path(twse ? "營利事業統一編號" : "UnifiedBusinessNo.").asText().strip();
            String listed = row.path(twse ? "上市日期" : "DateOfListing").asText().strip();
            String name = row.path(twse ? "公司簡稱" : "CompanyAbbreviation").asText().strip();
            if (validCode(code) && issuer.matches("[0-9]{8}") && !issuer.equals("00000000") && !listed.isEmpty()) {
                listings.add(new Listing(code, market, date(listed), issuer, name));
            }
        }
        return listings;
    }

    List<MarketEvent> stops(Market market) throws IOException, InterruptedException {
        if (market == Market.TWSE) {
            var table = http.fetch(market, TWSE + "/violation/stop?response=json").path("tables").path(0);
            return events(market, array(table.path("data")), 0, 1, 4, MarketEvent.Kind.HALTED);
        }
        var rows = array(http.fetchDocument(market, TPEX_OPEN + "/mopsfin_t187ap26_O"));
        var results = new ArrayList<MarketEvent>();
        for (JsonNode row : rows) {
            add(results, market, row.path("SecuritiesCompanyCode").asText(), row.path("CompanyName").asText(),
                    row.path("停止買賣開始日").asText(), MarketEvent.Kind.HALTED);
        }
        return results;
    }

    List<MarketEvent> halts(Market market) throws IOException, InterruptedException {
        boolean twse = market == Market.TWSE;
        var rows = array(http.fetchDocument(market, twse ? TWSE_OPEN + "/exchangeReport/TWTAWU"
                : TPEX_OPEN + "/tpex_spendi_history"));
        var results = new ArrayList<MarketEvent>();
        for (JsonNode row : rows) {
            String code = row.path(twse ? "Code" : "SecuritiesCompanyCode").asText();
            String name = row.path(twse ? "Name" : "CompanyName").asText();
            String start = row.path(twse ? "TradingHaltDate" : "DateOfSuspendedTrading").asText();
            String end = row.path(twse ? "TradingResumptionDate" : "DateOfResumedTrading").asText();
            // A date-only ledger still accepts trades before an intraday halt on that same date.
            String time = row.path(twse ? "TradingHaltTime" : "TimeOfSuspendedTrading").asText();
            if (!start.isBlank() && !start.equals(end)) {
                if (time.matches("[0-9]{6}") && time.compareTo("090000") > 0) {
                    start = date(start).plusDays(1).toString();
                }
                add(results, market, code, name, start, MarketEvent.Kind.HALTED);
            }
            add(results, market, code, name, end, MarketEvent.Kind.RESUMED);
        }
        return results;
    }

    private List<MarketEvent> events(Market market, JsonNode rows, int code, int name, int date,
                                     MarketEvent.Kind kind) throws IOException {
        var results = new ArrayList<MarketEvent>();
        for (JsonNode row : rows) {
            if (!row.isArray() || row.size() <= Math.max(date, Math.max(code, name))) {
                throw new IOException("Unexpected official event row");
            }
            add(results, market, row.path(code).asText(), row.path(name).asText(), row.path(date).asText(), kind);
        }
        return results;
    }

    private void add(List<MarketEvent> results, Market market, String code, String name, String effective,
                     MarketEvent.Kind kind) {
        if (validCode(code.strip()) && !effective.isBlank()) {
            results.add(new MarketEvent(code.strip(), market, date(effective), kind, name.strip()));
        }
    }

    private static JsonNode array(JsonNode rows) throws IOException {
        if (!rows.isArray()) {
            throw new IOException("Expected official table rows");
        }
        return rows;
    }

    private static boolean validCode(String code) {
        return code.matches("[0-9]{4}[0-9A-Z]{0,2}");
    }

    static LocalDate date(String value) {
        String digits = value.replaceAll("[^0-9]", "");
        int year = Integer.parseInt(digits.substring(0, digits.length() - 4));
        int month = Integer.parseInt(digits.substring(digits.length() - 4, digits.length() - 2));
        int day = Integer.parseInt(digits.substring(digits.length() - 2));
        return LocalDate.of(digits.length() == 8 ? year : year + 1911, month, day);
    }
}
