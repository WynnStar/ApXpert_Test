package tw.holdings.trade;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

import tw.holdings.profit.TradeCharges;

public final class TradeLedger {
    private static final BigDecimal ZERO_MONEY = new BigDecimal("0.00");

    private TradeLedger() {
    }

    /** 依交易日期回放。同日交易保留呼叫端提供的順序。 */
    public static TradePosition replay(String code, List<Trade> trades) {
        long shares = 0;
        BigDecimal cost = ZERO_MONEY;
        BigDecimal realized = ZERO_MONEY;

        for (Trade trade : trades.stream().sorted(Comparator.comparing(Trade::tradeDate, Comparator.nullsFirst(Comparator.naturalOrder()))).toList()) {
            BigDecimal quantity = BigDecimal.valueOf(trade.shares());
            BigDecimal amount = trade.unitPrice().multiply(quantity).setScale(2, RoundingMode.UNNECESSARY);
            BigDecimal fee = TradeCharges.fee(amount);

            if (trade.side() != Trade.Side.SELL) {
                shares = Math.addExact(shares, trade.shares());
                cost = cost.add(amount).add(fee);
                continue;
            }

            if (trade.shares() > shares) {
                throw new InsufficientSharesException(trade.tradeDate(), trade.shares(), shares);
            }
            // 最後一筆賣出分攤全部剩餘成本，避免四捨五入後留下成本餘額。
            BigDecimal allocated = trade.shares() == shares ? cost
                    : cost.multiply(quantity).divide(BigDecimal.valueOf(shares), 2, RoundingMode.HALF_UP);
            realized = realized.add(amount.subtract(fee).subtract(TradeCharges.sellTax(code, amount))
                    .subtract(allocated));
            shares -= trade.shares();
            cost = cost.subtract(allocated);
        }

        return new TradePosition(shares, cost, realized);
    }
}
