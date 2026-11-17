package tw.holdings.trade;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
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

    void updateTrade(long holdingId, TradeEntry original, Trade edited) {
        boolean sameDate = Objects.equals(original.trade().tradeDate(), edited.tradeDate());
        jdbc.update("""
                UPDATE holding_trades
                SET trade_date = ?, shares = ?, unit_price = ?,
                    sort_order = CASE WHEN ? THEN sort_order ELSE (
                        SELECT COALESCE(MAX(entry.sort_order), 0) + 1 FROM holding_trades entry
                        WHERE entry.holding_id = ? AND entry.trade_date = ?
                    ) END
                WHERE holding_id = ? AND id = ?
                """, edited.tradeDate(), edited.shares(), edited.unitPrice(), sameDate,
                holdingId, edited.tradeDate(), holdingId, original.id());
    }

    void deleteTrade(long holdingId, long tradeId) {
        jdbc.update("DELETE FROM holding_trades WHERE holding_id = ? AND id = ?", holdingId, tradeId);
    }

    void deleteByHolding(long id) {
        jdbc.update("DELETE FROM holding_trades WHERE holding_id = ?", id);
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
