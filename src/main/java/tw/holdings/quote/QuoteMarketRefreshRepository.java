package tw.holdings.quote;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 即使整批沒有任何行情，也能保存來源重試狀態。 */
@Repository
class QuoteMarketRefreshRepository {
    record State(Market market, LocalDate target, Instant windowStart, Instant lastAttempt,
                 Instant nextAttempt, Instant firstResponse, Instant recoveryStarted,
                 Instant completed, Instant normalAttempt) {}
    private final JdbcTemplate jdbc;
    QuoteMarketRefreshRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    Optional<State> find(Market market, LocalDate target) {
        return jdbc.query("SELECT * FROM quote_market_refresh WHERE market=? AND target_date=?",
                (r,i)->read(r),market.name(),target).stream().findFirst();
    }

    State obtain(Market market, LocalDate target, Instant windowStart) {
        try {
            jdbc.update("""
                    INSERT INTO quote_market_refresh (market,target_date,window_start_at)
                    SELECT ?,?,? WHERE NOT EXISTS
                    (SELECT 1 FROM quote_market_refresh WHERE market=? AND target_date=?)
                    """,market.name(),target,utc(windowStart),market.name(),target);
        } catch (DuplicateKeyException anotherCreator) {
            // 另一個呼叫已建立同市場、日期；保留它選定的偏移。
        }
        return find(market,target).orElseThrow();
    }

    void save(State s) {
        jdbc.update("""
                UPDATE quote_market_refresh SET last_attempt_at=?,next_attempt_at=?,first_response_at=?,
                  recovery_started_at=?,completed_at=?,normal_attempt_at=? WHERE market=? AND target_date=?
                """,utc(s.lastAttempt()),utc(s.nextAttempt()),utc(s.firstResponse()),utc(s.recoveryStarted()),
                utc(s.completed()),utc(s.normalAttempt()),s.market().name(),s.target());
    }

    private static State read(ResultSet r) throws SQLException {
        return new State(Market.valueOf(r.getString("market")),r.getObject("target_date",LocalDate.class),
                instant(r,"window_start_at"),instant(r,"last_attempt_at"),instant(r,"next_attempt_at"),
                instant(r,"first_response_at"),instant(r,"recovery_started_at"),instant(r,"completed_at"),
                instant(r,"normal_attempt_at"));
    }
    private static Instant instant(ResultSet r,String field) throws SQLException {
        var t=r.getObject(field,OffsetDateTime.class);return t==null?null:t.toInstant();
    }
    private static OffsetDateTime utc(Instant t) { return t==null?null:t.atOffset(ZoneOffset.UTC); }
}
