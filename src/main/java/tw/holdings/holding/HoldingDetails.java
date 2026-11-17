package tw.holdings.holding;

import java.math.BigDecimal;

public record HoldingDetails(long id, String code, int lots, BigDecimal buyPrice) {
}
