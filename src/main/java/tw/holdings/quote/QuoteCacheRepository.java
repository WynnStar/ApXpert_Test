package tw.holdings.quote;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class QuoteCacheRepository {
    private final JdbcTemplate jdbc;

    QuoteCacheRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<QuoteResult> find(LocalDate day, String code) {
        return jdbc.query("""
                SELECT status, stock_name, market, close_price, price_date
                FROM quote_cache WHERE lookup_date = ? AND code = ?
                """, (row, index) -> {
                    String market = row.getString("market");
                    return new QuoteResult(QuoteResult.Status.valueOf(row.getString("status")),
                            row.getString("stock_name"), market == null ? null : Market.valueOf(market),
                            row.getBigDecimal("close_price"), row.getObject("price_date", LocalDate.class));
                }, day, code).stream().findFirst();
    }

    void save(LocalDate day, String code, QuoteResult result) {
        jdbc.update("""
                MERGE INTO quote_cache (lookup_date, code, status, stock_name, market, close_price, price_date)
                KEY (lookup_date, code) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, day, code, result.status().name(), result.name(),
                result.market() == null ? null : result.market().name(), result.close(), result.priceDate());
    }
}
