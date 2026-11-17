package tw.holdings.holding;

import java.math.BigDecimal;
import java.time.LocalDate;

import tw.holdings.profit.PositionProfit;
import tw.holdings.quote.Market;
import tw.holdings.quote.marketstatus.MarketStatusService.Delisting;

public record HoldingQuoteView(long id, String code, int lots, BigDecimal buyPrice,
                               String name, Market market, BigDecimal close, LocalDate priceDate,
                               PositionProfit profit, Delisting delisting) {}
