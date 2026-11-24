package tw.holdings.quote.client;
import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tw.holdings.quote.Market;

/** 日曆介接入口；HTTP、HTML 解析與限流細節留在套件內。 */
@Component
public class OfficialCalendarClient {
    public record Day(boolean open,String reason) {}
    public record Snapshot(int year,Map<LocalDate,Day> days,String source) {
        public Snapshot { days=Map.copyOf(days); }
    }
    private final OfficialQuoteHttp http;
    private final String twse,tpex;
    OfficialCalendarClient(OfficialQuoteHttp http,
        @Value("${quotes.twse-calendar-url:https://www.twse.com.tw/rwd/zh/holidaySchedule/holidaySchedule?date=%d0101&response=json}") String twse,
        @Value("${quotes.tpex-calendar-url:https://www.tpex.org.tw/www/zh-tw/bulletin/tradingDate?date=%d&response=json}") String tpex) {
        this.http=http;this.twse=twse;this.tpex=tpex;
    }
    public Snapshot fetch(Market market,int year) throws IOException,InterruptedException {
        String url=(market==Market.TWSE?twse:tpex).formatted(year);
        return new Snapshot(year,HolidayCalendarParser.parse(http.fetchOptionalDocument(market,url),market,year),url);
    }
}
