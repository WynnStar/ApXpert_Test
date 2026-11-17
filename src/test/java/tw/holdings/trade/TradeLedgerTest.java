package tw.holdings.trade;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import tw.holdings.profit.ProfitCalculator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static tw.holdings.trade.Trade.Side.BUY;
import static tw.holdings.trade.Trade.Side.SELL;

class TradeLedgerTest {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 12);

    @Test
    void addsOddLotsAndChargesForEveryBuy() {
        var position = TradeLedger.replay("2330", List.of(
                trade(BUY, DAY, 300, "10.00"), trade(BUY, DAY.plusDays(1), 1_500, "12.00")));

        assertEquals(1_800, position.shares());
        assertEquals(new BigDecimal("21045.00"), position.remainingCost());
        assertEquals(new BigDecimal("11.6917"), position.averageCost());
        assertEquals(new BigDecimal("0.00"), position.realizedPnl());
        assertEquals(new BigDecimal("0.00"), position.realizedCost());
    }

    @Test
    void allocatesPartialSaleCostAndKeepsUnrealizedProfitSeparate() {
        var position = TradeLedger.replay("2330", List.of(
                trade(BUY, DAY, 300, "10.00"), trade(BUY, DAY.plusDays(1), 1_500, "12.00"),
                trade(SELL, DAY.plusDays(2), 400, "15.00")));

        assertEquals(1_400, position.shares());
        assertEquals(new BigDecimal("16368.33"), position.remainingCost());
        assertEquals(new BigDecimal("1285.33"), position.realizedPnl());
        assertEquals(new BigDecimal("4676.67"), position.realizedCost());
        assertEquals(new BigDecimal("4539.67"), position.unrealizedPnl("2330", new BigDecimal("15.00")));
        assertNull(position.unrealizedPnl("2330", null));
    }

    @Test
    void conservesCostAndCalculatesBothRatesWhenTheAllocationContainsCents() {
        var position = TradeLedger.replay("2330", List.of(
                trade(BUY, DAY, 1_000, "1000.00"), trade(SELL, DAY.plusDays(1), 500, "1100.00")));
        var unrealizedPnl = position.unrealizedPnl("2330", new BigDecimal("1050.00"));

        assertEquals(500, position.shares());
        assertEquals(new BigDecimal("500712.50"), position.realizedCost());
        assertEquals(new BigDecimal("500712.50"), position.remainingCost());
        assertEquals(new BigDecimal("1001425.00"), position.realizedCost().add(position.remainingCost()));
        assertEquals(new BigDecimal("46854.50"), position.realizedPnl());
        assertEquals(new BigDecimal("21964.50"), unrealizedPnl);
        assertEquals(new BigDecimal("9.36"),
                ProfitCalculator.returnPercentage(position.realizedPnl(), position.realizedCost()));
        assertEquals(new BigDecimal("4.39"),
                ProfitCalculator.returnPercentage(unrealizedPnl, position.remainingCost()));
    }

    @Test
    void closingEtfPositionUsesEtfTaxAndLeavesNoCost() {
        var position = TradeLedger.replay("0050", List.of(
                trade(BUY, DAY, 300, "10.01"), trade(SELL, DAY.plusDays(1), 300, "11.11")));

        assertEquals(0, position.shares());
        assertEquals(new BigDecimal("0.00"), position.remainingCost());
        assertEquals(new BigDecimal("287.00"), position.realizedPnl());
        assertNull(position.averageCost());
        assertEquals(new BigDecimal("0.00"), position.unrealizedPnl("0050", null));
    }

    @Test
    void lastSaleReleasesAllCostAfterRoundedPartialAllocation() {
        var position = TradeLedger.replay("2330", List.of(
                trade(BUY, DAY, 3, "10.00"),
                trade(SELL, DAY.plusDays(1), 1, "40.00"),
                trade(SELL, DAY.plusDays(2), 2, "40.00")));

        assertEquals(new BigDecimal("0.00"), position.remainingCost());
        assertEquals(new BigDecimal("30.00"), position.realizedPnl());
        assertEquals(new BigDecimal("50.00"), position.realizedCost());
    }

    @Test
    void preventsOversellingEvenIfLaterBuyWouldRestoreBalance() {
        assertThrows(InsufficientSharesException.class, () -> TradeLedger.replay("2330", List.of(
                trade(BUY, DAY, 300, "10.00"),
                trade(BUY, DAY.plusDays(2), 500, "12.00"),
                trade(SELL, DAY.plusDays(1), 301, "15.00"))));
    }

    private Trade trade(Trade.Side side, LocalDate date, long shares, String price) {
        return new Trade(side, date, shares, new BigDecimal(price));
    }
}
