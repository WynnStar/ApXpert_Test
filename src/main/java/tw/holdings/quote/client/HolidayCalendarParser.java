package tw.holdings.quote.client;
import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;
import com.fasterxml.jackson.databind.JsonNode;
import tw.holdings.quote.Market;

final class HolidayCalendarParser {
    private static final Pattern MONTH_DAY=Pattern.compile("(\\d{1,2})月\\s*(\\d{1,2})日");
    static Map<LocalDate,OfficialCalendarClient.Day> parse(JsonNode root,Market market,int year) throws IOException {
        try {
            Map<LocalDate,OfficialCalendarClient.Day> result=new HashMap<>();
            if(!"ok".equalsIgnoreCase(root.path("stat").asText()))throw new IOException("Invalid calendar status");
            if(market==Market.TWSE) {
                if(!"ok".equalsIgnoreCase(root.path("stat").asText()) || root.path("queryYear").asInt()!=year)
                    throw new IOException("Calendar year/status mismatch");
                var fields=root.path("fields");var rows=root.path("data");
                int date=index(fields,"日期"),name=index(fields,"名稱"),reason=index(fields,"說明");
                if(!rows.isArray() || rows.isEmpty())throw new IOException("Missing calendar rows");
                if(root.has("total") && root.path("total").asInt()!=rows.size())throw new IOException("Incomplete calendar row count");
                for(var row:rows) {
                    if(!row.isArray() || row.size()!=fields.size())throw new IOException("Malformed calendar row");
                    add(result,LocalDate.parse(row.get(date).asText()),row.get(name).asText(),row.get(reason).asText(),year);
                }
            } else {
                String html=root.path("data").path("html").asText();
                if(!Pattern.compile("中華民國\\s*"+(year-1911)+"\\s*年").matcher(html).find())
                    throw new IOException("Calendar HTML year mismatch");
                for(var row:CalendarHtmlTable.parse(html)) {
                    if(row.size()!=4) {
                        if(MONTH_DAY.matcher(String.join(" ",row)).find())throw new IOException("Malformed calendar table row");
                        continue;
                    }
                    if(row.get(0).contains("債券"))continue;
                    var matcher=MONTH_DAY.matcher(row.get(1));boolean hasDate=false;
                    while(matcher.find()) {
                        hasDate=true;add(result,LocalDate.of(year,Integer.parseInt(matcher.group(1)),Integer.parseInt(matcher.group(2))),row.get(0),row.get(3),year);
                    }
                    // 股票系統最後交易日的備註，另列僅交割而不交易的日期。
                    if(hasDate && row.get(0).contains("股票交易系統") && row.get(3).contains("市場無交易")) {
                        matcher=MONTH_DAY.matcher(row.get(3));
                        while(matcher.find())result.put(LocalDate.of(year,Integer.parseInt(matcher.group(1)),Integer.parseInt(matcher.group(2))),new OfficialCalendarClient.Day(false,"市場無交易，僅辦理給付結算"));
                    }
                }
            }
            if(result.values().stream().filter(d->!d.open()).count()<10 || !result.containsKey(LocalDate.of(year,1,1)))
                throw new IOException("Incomplete annual calendar");
            return Map.copyOf(result);
        } catch(RuntimeException malformed) { throw new IOException("Malformed official calendar",malformed); }
    }
    private static int index(JsonNode fields,String name) throws IOException {
        if(fields.isArray())for(int i=0;i<fields.size();i++)if(name.equals(fields.get(i).asText()))return i;
        throw new IOException("Missing calendar column: "+name);
    }
    private static void add(Map<LocalDate,OfficialCalendarClient.Day> out,LocalDate date,String name,String reason,int year) throws IOException {
        if(date.getYear()!=year)throw new IOException("Calendar date outside requested year");
        boolean open=name.contains("開始交易") || name.contains("最後交易");
        String details=name+" "+reason;
        boolean closed=details.contains("放假") || details.contains("補假") || details.contains("休市") || details.contains("市場無交易");
        if(!open && !closed)throw new IOException("Unknown calendar event type");
        out.put(date,new OfficialCalendarClient.Day(open,(name+" "+reason).strip()));
    }
}
