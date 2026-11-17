package tw.holdings.trade;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class TradeRepository {
    private final JdbcTemplate jdbc;

    TradeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<String> lockHoldingCode(long id) {
        return jdbc.query("SELECT code FROM holdings WHERE id = ? FOR UPDATE",
                (row, index) -> row.getString("code"), id).stream().findFirst();
    }

    List<Trade> findByHolding(long id) {
        return jdbc.query("""
                SELECT side, trade_date, shares, unit_price FROM holding_trades
                WHERE holding_id = ? ORDER BY trade_date, id
                """, (row, index) -> new Trade(Trade.Side.valueOf(row.getString("side")),
                row.getObject("trade_date", LocalDate.class), row.getLong("shares"),
                row.getBigDecimal("unit_price")), id);
    }

    void insert(long id, Trade trade) {
        jdbc.update("""
                INSERT INTO holding_trades (holding_id, side, trade_date, shares, unit_price)
                VALUES (?, ?, ?, ?, ?)
                """, id, trade.side().name(), trade.tradeDate(), trade.shares(), trade.unitPrice());
    }
}
