package tw.holdings.holding;

import java.math.BigDecimal;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import tw.holdings.profit.PortfolioSummary;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record SummaryApiResponse(BigDecimal totalCost, BigDecimal marketValue, BigDecimal pnl,
                          BigDecimal returnPct, int quoteMissing) {
    static SummaryApiResponse from(PortfolioSummary summary) {
        return new SummaryApiResponse(summary.totalCost(), summary.marketValue(), summary.pnl(),
                summary.returnPct(), summary.quoteMissing());
    }
}
