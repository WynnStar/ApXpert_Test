package tw.holdings.trade;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@JdbcTest
@Import({TradeLedgerService.class, TradeRepository.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TradeLedgerPersistenceTest {
    private static final LocalDate BUY_DATE = LocalDate.of(2026, 11, 12);

    @Autowired JdbcTemplate jdbc;
    @Autowired TradeLedgerService ledger;
    private long holdingId;

    @BeforeEach
    void seedHolding() {
        jdbc.update("DELETE FROM holding_trades");
        jdbc.update("DELETE FROM holdings");
        jdbc.update("INSERT INTO holdings (code, lots, buy_price) VALUES ('2330', 1, 10)");
        holdingId = jdbc.queryForObject("SELECT id FROM holdings WHERE code = '2330'", Long.class);
        ledger.recordTrade(holdingId, new Trade(Trade.Side.BUY, BUY_DATE, 300, new BigDecimal("10")));
    }

    @Test
    void rollsBackInvalidBackdatedSale() {
        assertThrows(InsufficientSharesException.class, () -> ledger.recordTrade(holdingId,
                new Trade(Trade.Side.SELL, BUY_DATE.minusDays(1), 1, new BigDecimal("12"))));
        assertEquals(1, tradeCount());
    }

    @Test
    void serializesConcurrentSalesAgainstTheSameHolding() throws Exception {
        var workers = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        Callable<Boolean> sell = () -> {
            start.await();
            try {
                ledger.recordTrade(holdingId,
                        new Trade(Trade.Side.SELL, BUY_DATE, 200, new BigDecimal("12")));
                return true;
            } catch (InsufficientSharesException rejected) {
                return false;
            }
        };
        try {
            var first = workers.submit(sell);
            var second = workers.submit(sell);
            start.countDown();
            int accepted = (first.get(10, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(10, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, accepted);
            assertEquals(2, tradeCount());
        } finally {
            workers.shutdownNow();
        }
    }

    private int tradeCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM holding_trades WHERE holding_id = ?", Integer.class, holdingId);
    }
}
