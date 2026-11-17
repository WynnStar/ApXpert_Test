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
    void upgradesAnExistingV1DatabaseWithoutChangingItsHoldings() throws Exception {
        String url = "jdbc:h2:file:" + temp.resolve("holdings").toString().replace('\\', '/');
        Flyway.configure().dataSource(url, "sa", "").target("3").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO holdings (id, code, lots, buy_price) VALUES (12, '0050', 2, 150)");
        }

        assertEquals(1, Flyway.configure().dataSource(url, "sa", "").load().migrate().migrationsExecuted);
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT id, code, lots, buy_price FROM holdings")) {
                assertTrue(rows.next());
                assertEquals(12, rows.getLong("id"));
                assertEquals("0050", rows.getString("code"));
                assertEquals(2, rows.getInt("lots"));
                assertEquals("150.00", rows.getBigDecimal("buy_price").toPlainString());
            }
            try (var trades = statement.executeQuery("SELECT COUNT(*) FROM holding_trades")) {
                assertTrue(trades.next());
                assertEquals(0, trades.getInt(1));
            }
        }
    }
}
