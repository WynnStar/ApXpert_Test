package tw.holdings.holding.legacy;

import java.math.BigDecimal;

import tw.holdings.quote.Market;

public record HoldingDetails(long id, String code, int lots, BigDecimal buyPrice, String name, Market market) {
}
