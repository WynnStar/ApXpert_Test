package tw.holdings.quote.marketstatus;

import java.time.LocalDate;

import tw.holdings.quote.Market;
import tw.holdings.quote.client.OfficialMarketClient;

record MarketEvent(String code, Market market, LocalDate date, Kind kind, String name) {
    enum Kind { DELISTED, RELISTED, HALTED, RESUMED }

    static MarketEvent fromOfficial(OfficialMarketClient.Event event) {
        Kind kind = switch (event.kind()) {
            case DELISTED -> Kind.DELISTED;
            case RELISTED -> Kind.RELISTED;
            case HALTED -> Kind.HALTED;
            case RESUMED -> Kind.RESUMED;
        };
        return new MarketEvent(event.code(), event.market(), event.date(), kind, event.name());
    }
}
