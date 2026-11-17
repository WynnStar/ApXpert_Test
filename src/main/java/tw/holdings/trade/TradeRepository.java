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

    List<TradeEntry> findByHolding(long id) {
        return jdbc.query("""
                SELECT id, sort_order, side, trade_date, shares, unit_price FROM holding_trades
                WHERE holding_id = ? ORDER BY trade_date NULLS FIRST, sort_order, id
                """, (row, index) -> new TradeEntry(row.getLong("id"), row.getLong("sort_order"),
                new Trade(Trade.Side.valueOf(row.getString("side")), row.getObject("trade_date", LocalDate.class),
                        row.getLong("shares"), row.getBigDecimal("unit_price"))), id);
    }

    void swapOrder(long holdingId, TradeEntry first, TradeEntry second) {
        jdbc.update("""
                UPDATE holding_trades
                SET sort_order = CASE WHEN id = ? THEN ? ELSE ? END
                WHERE holding_id = ? AND id IN (?, ?)
                """, first.id(), second.sortOrder(), first.sortOrder(), holdingId, first.id(), second.id());
    }

    void removeOpening(long id) {
        jdbc.update("DELETE FROM holding_trades WHERE holding_id = ? AND side = 'OPENING'", id);
    }

    void insert(long id, Trade trade) {
        jdbc.update("""
                INSERT INTO holding_trades (holding_id, side, trade_date, shares, unit_price, sort_order)
                VALUES (?, ?, ?, ?, ?, (
                    SELECT COALESCE(MAX(sort_order), 0) + 1 FROM holding_trades
                    WHERE holding_id = ? AND trade_date = ?
                ))
                """, id, trade.side().name(), trade.tradeDate(), trade.shares(), trade.unitPrice(),
                id, trade.tradeDate());
    }
}
