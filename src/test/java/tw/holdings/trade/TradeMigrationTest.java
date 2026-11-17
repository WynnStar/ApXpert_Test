package tw.holdings.trade;

import java.nio.file.Path;
import java.sql.DriverManager;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeMigrationTest {
    @TempDir Path temp;

    @Test
    void keepsTheExistingSameDayOrderWhenUpgradingFromV6() throws Exception {
        String url = "jdbc:h2:file:" + temp.resolve("trade-order").toString().replace('\\', '/');
        Flyway.configure().dataSource(url, "sa", "").target("6").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO holdings (id, code, lots, buy_price) VALUES (12, '0050', 2, 150)");
            statement.executeUpdate("""
                    INSERT INTO holding_trades (id, holding_id, side, trade_date, shares, unit_price)
                    VALUES (25, 12, 'SELL', DATE '2026-11-16', 10, 155),
                           (23, 12, 'BUY', DATE '2026-11-16', 10, 150)
                    """);
        }

        assertEquals(1, Flyway.configure().dataSource(url, "sa", "").load().migrate().migrationsExecuted);
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement();
             var trades = statement.executeQuery(
                     "SELECT side FROM holding_trades WHERE holding_id = 12 ORDER BY trade_date, sort_order, id")) {
            assertTrue(trades.next());
            assertEquals("BUY", trades.getString("side"));
            assertTrue(trades.next());
            assertEquals("SELL", trades.getString("side"));
        }
    }

    @Test
    void upgradesAnExistingV1DatabaseWithoutChangingItsHoldings() throws Exception {
        String url = "jdbc:h2:file:" + temp.resolve("holdings").toString().replace('\\', '/');
        Flyway.configure().dataSource(url, "sa", "").target("3").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO holdings (id, code, lots, buy_price) VALUES (12, '0050', 2, 150)");
        }

        assertEquals(4, Flyway.configure().dataSource(url, "sa", "").load().migrate().migrationsExecuted);
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT id, code, lots, buy_price FROM holdings")) {
                assertTrue(rows.next());
                assertEquals(12, rows.getLong("id"));
                assertEquals("0050", rows.getString("code"));
                assertEquals(2, rows.getInt("lots"));
                assertEquals("150.00", rows.getBigDecimal("buy_price").toPlainString());
            }
            try (var trades = statement.executeQuery(
                    "SELECT holding_id, side, trade_date, shares, unit_price FROM holding_trades")) {
                assertTrue(trades.next());
                assertEquals(12, trades.getLong("holding_id"));
                assertEquals("OPENING", trades.getString("side"));
                org.junit.jupiter.api.Assertions.assertNull(trades.getDate("trade_date"));
                assertEquals(2000, trades.getLong("shares"));
                assertEquals("150.00", trades.getBigDecimal("unit_price").toPlainString());
                org.junit.jupiter.api.Assertions.assertFalse(trades.next());
            }
        }
    }
}
