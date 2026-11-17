package tw.holdings.holding;

import java.math.BigDecimal;
import java.time.LocalDate;

import tw.holdings.quote.Market;

public record HoldingQuoteView(long id, String code, int lots, BigDecimal buyPrice,
                               String name, Market market, BigDecimal close, LocalDate priceDate) {}
