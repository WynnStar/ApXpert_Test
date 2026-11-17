package tw.holdings.holding;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tw.holdings.quote.QuoteResult;
import tw.holdings.quote.QuoteService;

@RestController
class StockLookupController {
    private final QuoteService quotes;

    StockLookupController(QuoteService quotes) {
        this.quotes = quotes;
    }

    @GetMapping("/holdings/stock-lookup")
    ResponseEntity<Map<String, String>> lookup(@RequestParam String code) {
        if (!code.matches(CreateHoldingForm.CODE_PATTERN)) {
            return ResponseEntity.badRequest().body(Map.of("message", "股票代號格式錯誤"));
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
