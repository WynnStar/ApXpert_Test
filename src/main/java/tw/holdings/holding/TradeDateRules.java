package tw.holdings.holding;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.stereotype.Service;
import tw.holdings.quote.MarketStatusService;

@Service
class TradeDateRules {
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private final Clock clock;
    private final MarketStatusService markets;

    TradeDateRules(Clock clock, MarketStatusService markets) {
        this.clock = clock;
        this.markets = markets;
    }

    record Range(LocalDate minDate, LocalDate maxDate, LocalDate firstListingDate) {}

    LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), TAIPEI);
    }

    Range newTradeRange(String code) {
        LocalDate today = today();
        LocalDate floor = today.minusYears(5);
        LocalDate listing = firstListingDate(code);
        return new Range(listing != null && listing.isAfter(floor) ? listing : floor, today, listing);
    }

    Range editRange(String code) {
        LocalDate listing = firstListingDate(code);
        return new Range(listing, today(), listing);
    }

    void validateNew(String code, LocalDate date, LocalDate today) {
        validateLatest(date, today);
        LocalDate floor = today.minusYears(5);
        if (date.isBefore(floor)) {
            throw new HoldingApiController.ApiValidationException("新增交易最早可填寫 " + floor + "（五年前）");
        }
        validateListing(code, date);
    }

    void validateEdit(String code, LocalDate date) {
        validateLatest(date, today());
        validateListing(code, date);
    }

    private void validateLatest(LocalDate date, LocalDate today) {
        if (date.isAfter(today)) {
            throw new HoldingApiController.ApiValidationException("交易日期不能晚於今天");
        }
    }

    private void validateListing(String code, LocalDate date) {
        LocalDate listing = firstListingDate(code);
        if (listing != null && date.isBefore(listing)) {
            throw new HoldingApiController.ApiValidationException("交易日期不得早於首次上市或上櫃日 " + listing);
        }
    }

    private LocalDate firstListingDate(String code) {
        if (code == null || !code.matches(CreateHoldingForm.CODE_PATTERN)) {
            return null;
        }
        return markets.firstListingDate(code).orElse(null);
    }
}
