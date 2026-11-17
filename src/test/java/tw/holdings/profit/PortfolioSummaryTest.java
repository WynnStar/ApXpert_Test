package tw.holdings.profit;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PortfolioSummaryTest {
    @Test
    void emptyPortfolioHasZeroTotalsAndAZeroRate() {
        assertEquals(new PortfolioSummary(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("0.00"), 0), PortfolioSummary.from(List.of()));
    }

    @Test
    void excludesMissingQuotesFromMarketValueAndProfitButIncludesTheirCost() {
        PositionProfit priced = ProfitCalculator.calculate("2330", 1,
                new BigDecimal("1000.00"), new BigDecimal("1100.00"));
        PositionProfit missing = ProfitCalculator.calculate("0050", 2,
                new BigDecimal("150.00"), null);

        assertEquals(new PositionProfit(new BigDecimal("300427"), null, null, null), missing);
        assertEquals(new PortfolioSummary(new BigDecimal("1301852"), new BigDecimal("1100000"),
                new BigDecimal("93708"), new BigDecimal("7.20"), 1),
                PortfolioSummary.from(List.of(priced, missing)));
    }

    @Test
    void calculatesOverallReturnFromCombinedProfitAndCost() {
        PositionProfit stock = ProfitCalculator.calculate("2330", 1,
                new BigDecimal("1000.00"), new BigDecimal("1100.00"));
        PositionProfit etf = ProfitCalculator.calculate("0050", 2,
                new BigDecimal("150.00"), new BigDecimal("145.50"));

        assertEquals(new PortfolioSummary(new BigDecimal("1301852"), new BigDecimal("1391000"),
                new BigDecimal("83576"), new BigDecimal("6.42"), 0),
                PortfolioSummary.from(List.of(stock, etf)));
    }
}
