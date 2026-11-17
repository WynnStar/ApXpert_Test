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

    @Test
    void laterBuyChangesRemainingAverageCostButKeepsPriorRealizedProfit() {
        Trade firstBuy = trade(BUY, DAY, 1_000, "10.00");
        Trade sale = trade(SELL, DAY.plusDays(1), 400, "12.00");
        var beforeLaterBuy = TradeLedger.replay("2330", List.of(firstBuy, sale));
        var afterLaterBuy = TradeLedger.replay("2330", List.of(
                firstBuy, sale, trade(BUY, DAY.plusDays(2), 400, "20.00")));

        // 買進成本 10,020；賣出分攤 4,008，淨損益為 4,800 - 20 - 14 - 4,008。
        assertEquals(600, beforeLaterBuy.shares());
        assertEquals(new BigDecimal("6012.00"), beforeLaterBuy.remainingCost());
        assertEquals(new BigDecimal("10.0200"), beforeLaterBuy.averageCost());
        assertEquals(new BigDecimal("4008.00"), beforeLaterBuy.realizedCost());
        assertEquals(new BigDecimal("758.00"), beforeLaterBuy.realizedPnl());

        // 後買進的 8,020 元僅加入剩餘成本，不能回頭改變先前賣出的成本或損益。
        assertEquals(1_000, afterLaterBuy.shares());
        assertEquals(new BigDecimal("14032.00"), afterLaterBuy.remainingCost());
        assertEquals(new BigDecimal("14.0320"), afterLaterBuy.averageCost());
        assertEquals(beforeLaterBuy.realizedCost(), afterLaterBuy.realizedCost());
        assertEquals(beforeLaterBuy.realizedPnl(), afterLaterBuy.realizedPnl());
        assertEquals(new BigDecimal("18040.00"),
                afterLaterBuy.realizedCost().add(afterLaterBuy.remainingCost()));
        assertEquals(new BigDecimal("902.00"),
                afterLaterBuy.unrealizedPnl("2330", new BigDecimal("15.00")));
    }

    @Test
    void separateSmallSalesEachPayTheMinimumFee() {
        Trade buy = trade(BUY, DAY, 1_000, "10.00");
        var splitSales = TradeLedger.replay("2330", List.of(
                buy, trade(SELL, DAY.plusDays(1), 500, "20.00"),
                trade(SELL, DAY.plusDays(2), 500, "20.00")));
        var singleSale = TradeLedger.replay("2330", List.of(
                buy, trade(SELL, DAY.plusDays(1), 1_000, "20.00")));

        // 分兩次各賣 10,000 元，各收 20 元手續費；一次賣 20,000 元收 28 元。
        assertEquals(0, splitSales.shares());
        assertEquals(new BigDecimal("0.00"), splitSales.remainingCost());
        assertEquals(new BigDecimal("10020.00"), splitSales.realizedCost());
        assertEquals(new BigDecimal("9880.00"), splitSales.realizedPnl());
        assertEquals(new BigDecimal("9892.00"), singleSale.realizedPnl());
        assertEquals(new BigDecimal("12.00"),
                singleSale.realizedPnl().subtract(splitSales.realizedPnl()));
    }

    @Test
    void multipleBuysAndSalesConserveAllCostsUntilTheLastShareIsSold() {
        Trade firstBuy = trade(BUY, DAY, 300, "10.01");
        Trade secondBuy = trade(BUY, DAY.plusDays(1), 700, "12.34");
        Trade firstSale = trade(SELL, DAY.plusDays(2), 333, "15.00");
        var partiallySold = TradeLedger.replay("2330", List.of(firstBuy, secondBuy, firstSale));
        var closed = TradeLedger.replay("2330", List.of(
                firstBuy, secondBuy, firstSale, trade(SELL, DAY.plusDays(3), 667, "16.00")));

        // 兩筆買進成本為 3,003 + 20 + 8,638 + 20 = 11,681 元。
        // 首次賣出分攤 11,681 × 333 / 1,000 = 3,889.77，剩餘成本取差額。
        assertEquals(667, partiallySold.shares());
        assertEquals(new BigDecimal("3889.77"), partiallySold.realizedCost());
        assertEquals(new BigDecimal("7791.23"), partiallySold.remainingCost());
        assertEquals(new BigDecimal("11.6810"), partiallySold.averageCost());
        assertEquals(new BigDecimal("1071.23"), partiallySold.realizedPnl());
        assertEquals(new BigDecimal("11681.00"),
                partiallySold.realizedCost().add(partiallySold.remainingCost()));

        // 淨賣出收入為 (4,995 - 20 - 14) + (10,672 - 20 - 32) = 15,581 元。
        assertEquals(0, closed.shares());
        assertEquals(new BigDecimal("0.00"), closed.remainingCost());
        assertEquals(new BigDecimal("11681.00"), closed.realizedCost());
        assertEquals(new BigDecimal("3900.00"), closed.realizedPnl());
        assertNull(closed.averageCost());
        assertEquals(new BigDecimal("0.00"), closed.unrealizedPnl("2330", null));
    }

    private Trade trade(Trade.Side side, LocalDate date, long shares, String price) {
        return new Trade(side, date, shares, new BigDecimal(price));
    }
}
