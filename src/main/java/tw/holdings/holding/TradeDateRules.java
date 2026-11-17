package tw.holdings.holding;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.stereotype.Service;
import tw.holdings.quote.marketstatus.MarketStatusService;

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

    Range range(String code) {
        LocalDate today = today();
        LocalDate floor = fiveYearFloor(today);
        LocalDate listing = firstListingDate(code);
        return new Range(listing != null && listing.isAfter(floor) ? listing : floor, today, listing);
    }

    void validateNew(String code, LocalDate date, LocalDate today) {
        validateDateInRange(code, date, today, "新增交易");
    }

    boolean editDateLocked(LocalDate originalDate, LocalDate today) {
        return originalDate != null && originalDate.isBefore(fiveYearFloor(today));
    }

    void validateEdit(String code, LocalDate originalDate, LocalDate date) {
        LocalDate today = today();
        if (editDateLocked(originalDate, today)) {
            if (!originalDate.equals(date)) {
                throw new HoldingValidationException("超過五年的交易不可修改日期");
            }
            validateListing(code, date);
            return;
        }
        validateDateInRange(code, date, today, "修改交易日期");
    }

    private void validateDateInRange(String code, LocalDate date, LocalDate today, String operation) {
        validateLatest(date, today);
        LocalDate floor = fiveYearFloor(today);
        if (date.isBefore(floor)) {
            throw new HoldingValidationException(operation + "最早可填寫 " + floor + "（五年前）");
        }
        validateListing(code, date);
    }

    private LocalDate fiveYearFloor(LocalDate today) {
        return today.minusYears(5);
    }

    private void validateLatest(LocalDate date, LocalDate today) {
        if (date.isAfter(today)) {
            throw new HoldingValidationException("交易日期不能晚於今天");
        }
    }

    private void validateListing(String code, LocalDate date) {
        LocalDate listing = firstListingDate(code);
        if (listing != null && date.isBefore(listing)) {
            throw new HoldingValidationException("交易日期不得早於首次上市或上櫃日 " + listing);
        }
    }

    private LocalDate firstListingDate(String code) {
        if (code == null || !code.matches(CreateHoldingForm.CODE_PATTERN)) {
            return null;
        }
        return markets.firstListingDate(code).orElse(null);
    }
}
