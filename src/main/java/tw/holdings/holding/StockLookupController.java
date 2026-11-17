package tw.holdings.holding;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tw.holdings.quote.QuoteResult;
import tw.holdings.quote.QuoteService;
import tw.holdings.quote.marketstatus.MarketStatusService;

@RestController
class StockLookupController {
    private final QuoteService quotes;
    private final MarketStatusService markets;

    StockLookupController(QuoteService quotes, MarketStatusService markets) {
        this.quotes = quotes;
        this.markets = markets;
    }

    @GetMapping("/holdings/stock-lookup")
    ResponseEntity<Map<String, String>> lookup(@RequestParam String code) {
        if (!code.matches(StockCode.PATTERN)) {
            return ResponseEntity.badRequest().body(Map.of("message", "股票代號格式錯誤"));
        }
        var delisting = markets.delisting(code);
        if (delisting.isPresent()) {
            return ResponseEntity.ok(Map.of("message", code + " " + delisting.get().label()));
        }
        QuoteResult result = quotes.lookup(code, null);
        String message = switch (result.status()) {
            case FOUND -> result.name() + "（" + result.market().getLabel() + "）";
            case NOT_FOUND -> "查無此股票代號";
            case UNAVAILABLE -> "暫時無法驗證股票代號，請稍後再試";
        };
        return ResponseEntity.ok(Map.of("message", message));
    }
}
