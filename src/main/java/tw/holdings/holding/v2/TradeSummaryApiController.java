package tw.holdings.holding.v2;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tw.holdings.holding.api.HoldingJsonApi;

@RestController
@HoldingJsonApi
class TradeSummaryApiController {
    private final TradeHoldingQueryService queries;

    TradeSummaryApiController(TradeHoldingQueryService queries) {
        this.queries = queries;
    }

    @GetMapping(path = "/api/v2/summary", produces = MediaType.APPLICATION_JSON_VALUE)
    TradePortfolioSummary summary() {
        return queries.summary();
    }
}
