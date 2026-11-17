package tw.holdings.quote;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class QuoteCacheRepository {
    record Entry(QuoteResult result, int failureCount, int postCloseAttempts,
                 Instant nextAttemptAt, Instant lastAttemptAt) {
        Entry(QuoteResult result, int failureCount, int postCloseAttempts, Instant nextAttemptAt) {
            this(result, failureCount, postCloseAttempts, nextAttemptAt, null);
        }
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
                       failure_count, post_close_attempts, next_attempt_at, last_attempt_at
                FROM quote_cache WHERE lookup_date = ? AND code = ?
                """, (row, index) -> readEntry(row), day, code).stream().findFirst();
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

    List<DueCode> dueAfterClose(ZonedDateTime now) {
        return jdbc.query("""
                SELECT h.code, COALESCE(c.market, h.market) AS market
                FROM holdings h LEFT JOIN quote_cache c ON c.code = h.code AND c.lookup_date = ?
                WHERE (c.status IS NULL OR c.status <> 'NOT_FOUND')
                    AND (c.price_date IS NULL OR c.price_date < ?)
                    AND (c.last_attempt_at IS NULL OR c.last_attempt_at < ?)
                    AND (c.next_attempt_at IS NULL OR c.next_attempt_at <= ?)
                ORDER BY c.next_attempt_at ASC NULLS FIRST, h.code
                """, (row, index) -> new DueCode(row.getString("code"), readMarket(row)),
                now.toLocalDate(), now.toLocalDate(), utc(QuoteRefreshPolicy.slotStart(now)), utc(now.toInstant()));
    }

    Optional<Entry> lastMarketAttempt(LocalDate day, Market market) {
        return jdbc.query("""
                SELECT status, stock_name, market, close_price, price_date, failure_count,
                       post_close_attempts, next_attempt_at, last_attempt_at
                FROM quote_cache WHERE lookup_date = ? AND market = ? AND last_attempt_at IS NOT NULL
                ORDER BY last_attempt_at DESC, price_date DESC NULLS LAST FETCH FIRST 1 ROW ONLY
                """, (row, index) -> readEntry(row), day, market.name()).stream().findFirst();
    }

    void markBatchFailure(ZonedDateTime now, Market market) {
        jdbc.update("""
                UPDATE quote_cache SET failure_count = failure_count + 1,
                    post_close_attempts = post_close_attempts + ?, last_attempt_at = ?, next_attempt_at = ?
                WHERE lookup_date = ? AND market = ?
                """, QuoteRefreshPolicy.isPostClose(now) ? 1 : 0, utc(now.toInstant()),
                utc(QuoteRefreshPolicy.nextAttemptAt(now, true)), now.toLocalDate(), market.name());
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

    void saveBatch(ZonedDateTime now, Map<String, QuoteResult> quotes) {
        LocalDate day = now.toLocalDate();
        Map<String, Entry> daily = new HashMap<>();
        jdbc.query("""
                SELECT code, status, stock_name, market, close_price, price_date,
                       failure_count, post_close_attempts, next_attempt_at, last_attempt_at
                FROM quote_cache WHERE lookup_date = ?
                """, (org.springframework.jdbc.core.RowCallbackHandler) row -> {
                    daily.put(row.getString("code"), readEntry(row));
                }, day);
        Map<String, QuoteResult> latest = new HashMap<>();
        jdbc.query("""
                SELECT code, status, stock_name, market, close_price, price_date
                FROM quote_cache WHERE lookup_date <= ? AND status = 'FOUND'
                QUALIFY ROW_NUMBER() OVER (
                    PARTITION BY code ORDER BY price_date DESC NULLS LAST, lookup_date DESC
                ) = 1
                """, (org.springframework.jdbc.core.RowCallbackHandler) row ->
                        latest.put(row.getString("code"), readResult(row)), day);

        var values = new ArrayList<Object[]>();
        boolean postClose = QuoteRefreshPolicy.isPostClose(now);
        Instant next = QuoteRefreshPolicy.nextAttemptAt(now, false);
        for (var item : quotes.entrySet()) {
            QuoteResult result = item.getValue();
            QuoteResult prior = latest.get(item.getKey());
            // 官方批次檔可能較晚更新，保留較新的實際交易價格。
            if (prior != null && prior.priceDate() != null
                    && (result.priceDate() == null || result.priceDate().isBefore(prior.priceDate()))) {
                result = prior;
            }
            Entry old = daily.get(item.getKey());
            int attempts = (old == null ? 0 : old.postCloseAttempts()) + (postClose ? 1 : 0);
            values.add(new Object[] { day, item.getKey(), result.status().name(), result.name(),
                    result.market().name(), result.close(), result.priceDate(), utc(now.toInstant()),
                    utc(next), 0, attempts });
        }
        jdbc.batchUpdate("""
                MERGE INTO quote_cache (lookup_date, code, status, stock_name, market, close_price, price_date,
                    last_attempt_at, next_attempt_at, failure_count, post_close_attempts)
                KEY (lookup_date, code) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, values);
    }

    private static Entry readEntry(ResultSet row) throws SQLException {
        OffsetDateTime next = row.getObject("next_attempt_at", OffsetDateTime.class);
        OffsetDateTime last = row.getObject("last_attempt_at", OffsetDateTime.class);
        return new Entry(readResult(row), row.getInt("failure_count"), row.getInt("post_close_attempts"),
                next == null ? null : next.toInstant(), last == null ? null : last.toInstant());
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
