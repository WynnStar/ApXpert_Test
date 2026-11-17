package tw.holdings.quote;

import java.time.LocalDate;

record MarketEvent(String code, Market market, LocalDate date, Kind kind, String name) {
    enum Kind { DELISTED, RELISTED, HALTED, RESUMED }
}
