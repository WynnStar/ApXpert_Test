package tw.holdings.quote.client;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;
import tw.holdings.quote.Market;
import static org.junit.jupiter.api.Assertions.*;

class OfficialCalendarClientTest {
    @ParameterizedTest @EnumSource(Market.class)
    void fetchesRequestedYearFromConfiguredOfficialCalendarUrl(Market market) throws Exception {
        byte[] body;
        try(var in=getClass().getResourceAsStream("/quotes/calendar-"+(market==Market.TWSE?"twse":"tpex")+"-2026.json")){body=in.readAllBytes();}
        var query=new AtomicReference<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/calendar",e->{query.set(e.getRequestURI().getQuery());e.sendResponseHeaders(200,body.length);try(var out=e.getResponseBody()){out.write(body);}});
        server.start();
        try {
            var clock=Clock.fixed(Instant.parse("2026-11-24T00:00:00Z"),ZoneOffset.UTC);
            var http=new OfficialQuoteHttp(new ObjectMapper(),new OfficialQuoteRateLimit(Duration.ZERO,Duration.ZERO),clock);
            var url="http://127.0.0.1:"+server.getAddress().getPort()+"/calendar?date=%d";
            var result=new OfficialCalendarClient(http,url,url).fetch(market,2026);
            assertEquals("date=2026",query.get());assertEquals(2026,result.year());
            assertFalse(result.days().get(LocalDate.of(2026,1,1)).open());
        } finally {server.stop(0);}
    }
    @Test void optionalCalendar404DoesNotCoolDownThePriceSource() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/missing",e->{e.sendResponseHeaders(404,-1);e.close();});
        server.createContext("/price",e->{byte[] body="{\"stat\":\"ok\"}".getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,body.length);try(var out=e.getResponseBody()){out.write(body);}});
        server.start();
        try {
            var http=new OfficialQuoteHttp(new ObjectMapper(),new OfficialQuoteRateLimit(Duration.ZERO,Duration.ZERO),Clock.systemUTC());
            var base="http://127.0.0.1:"+server.getAddress().getPort();
            assertThrows(java.io.IOException.class,()->http.fetchOptionalDocument(Market.TWSE,base+"/missing"));
            assertEquals("ok",http.fetchDocument(Market.TWSE,base+"/price").get("stat").asText());
        } finally {server.stop(0);}
    }
}
