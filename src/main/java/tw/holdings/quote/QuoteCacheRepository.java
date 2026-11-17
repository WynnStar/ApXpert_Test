package tw.holdings.quote;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class QuoteCacheRepository {
    record Entry(QuoteResult result, int failureCount, int postCloseAttempts, Instant nextAttemptAt) {
    }

    record DueCode(String code, Market market) {
    }

    private final JdbcTemplate jdbc;

    QuoteCacheRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Entry> find(LocalDate day, String code) {
        return jdbc.query("""
                SELECT status, stock_name, market, close_price, price_date,
                       failure_count, post_close_attempts, next_attempt_at
                FROM quote_cache WHERE lookup_date = ? AND code = ?
                """, (row, index) -> {
                    OffsetDateTime next = row.getObject("next_attempt_at", OffsetDateTime.class);
                    return new Entry(readResult(row), row.getInt("failure_count"),
                            row.getInt("post_close_attempts"), next == null ? null : next.toInstant());
                }, day, code).stream().findFirst();
    }

    Optional<QuoteResult> latestFound(LocalDate day, String code) {
        return latestFound(day, code, null, null);
    }

    Optional<QuoteResult> latestFound(LocalDate day, String code, LocalDate since, Market market) {
        return jdbc.query("""
                SELECT status, stock_name, market, close_price, price_date
                FROM quote_cache WHERE code = ? AND lookup_date <= ? AND status = 'FOUND'
                    AND (CAST(? AS DATE) IS NULL OR price_date >= ?)
                    AND (CAST(? AS VARCHAR) IS NULL OR market = ?)
                ORDER BY price_date DESC NULLS LAST, lookup_date DESC FETCH FIRST 1 ROW ONLY
                """, (row, index) -> readResult(row), code, day, since, since,
                market == null ? null : market.name(), market == null ? null : market.name()).stream().findFirst();
    }

    List<DueCode> dueAfterClose(LocalDate day, Instant now) {
        return jdbc.query("""
                SELECT code, market FROM quote_cache
                WHERE lookup_date = ? AND status <> 'NOT_FOUND'
                    AND (price_date IS NULL OR price_date < ?)
                    AND post_close_attempts < ?
                    AND (next_attempt_at IS NULL OR next_attempt_at <= ?)
                ORDER BY next_attempt_at ASC NULLS FIRST, code
                """, (row, index) -> new DueCode(row.getString("code"), readMarket(row)),
                day, day, QuoteRefreshPolicy.MAX_POST_CLOSE_ATTEMPTS, utc(now));
    }

    void save(LocalDate day, String code, Entry entry, Instant attemptedAt) {
        QuoteResult result = entry.result();
        jdbc.update("""
                MERGE INTO quote_cache (lookup_date, code, status, stock_name, market, close_price, price_date,
                    last_attempt_at, next_attempt_at, failure_count, post_close_attempts)
                KEY (lookup_date, code) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, day, code, result.status().name(), result.name(),
                result.market() == null ? null : result.market().name(), result.close(), result.priceDate(),
                utc(attemptedAt), utc(entry.nextAttemptAt()), entry.failureCount(), entry.postCloseAttempts());
    }

    private static QuoteResult readResult(ResultSet row) throws SQLException {
        return new QuoteResult(QuoteResult.Status.valueOf(row.getString("status")),
                row.getString("stock_name"), readMarket(row), row.getBigDecimal("close_price"),
                row.getObject("price_date", LocalDate.class));
    }

    private static Market readMarket(ResultSet row) throws SQLException {
        String market = row.getString("market");
        return market == null ? null : Market.valueOf(market);
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
