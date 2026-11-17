package tw.holdings.holding.legacy;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import tw.holdings.holding.error.StockIdentityUnavailableException;
import tw.holdings.quote.Market;

@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record HoldingApiResponse(long id, String code, String name, Market market, int lots,
                          BigDecimal buyPrice, BigDecimal currentPrice,
                          @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate priceDate,
                          BigDecimal pnl, BigDecimal returnPct) {
    static HoldingApiResponse from(HoldingQuoteView holding) {
        if (holding.name() == null || holding.market() == null) {
            throw new StockIdentityUnavailableException(holding.code());
        }
        return new HoldingApiResponse(holding.id(), holding.code(), holding.name(), holding.market(),
                holding.lots(), holding.buyPrice(), holding.close(), holding.priceDate(),
                holding.profit().pnl(), holding.profit().returnPct());
    }
}
