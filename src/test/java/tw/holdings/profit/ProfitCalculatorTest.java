package tw.holdings.profit;

import java.math.BigDecimal;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProfitCalculatorTest {
    @ParameterizedTest
    @CsvSource({
        "2330, 1, 1000.00, 1100.00, 1001425, 1100000, 93708, 9.36",
        "0050, 2, 150.00, 145.50, 300427, 291000, -10132, -3.37",
        "2363, 1, 10.00, 10.50, 10020, 10500, 429, 4.28"
    })
    void calculatesExamExamples(String code, int lots, BigDecimal buyPrice, BigDecimal close,
                                BigDecimal cost, BigDecimal marketValue, BigDecimal pnl, BigDecimal rate) {
        assertEquals(new PositionProfit(cost, marketValue, pnl, rate),
                ProfitCalculator.calculate(code, lots, buyPrice, close));
    }
}
