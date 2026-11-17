package tw.holdings.quote.marketstatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tw.holdings.quote.Market;

@Repository
class MarketStatusRepository {
    record Identity(String issuerId, LocalDate listedOn) {}
    record FinalClose(LocalDate nextMonth, LocalDate priceDate, BigDecimal close) {}

    private final JdbcTemplate jdbc;

    MarketStatusRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    boolean claimDay(LocalDate date) {
        try {
            jdbc.update("INSERT INTO market_sync_day (sync_date) VALUES (?)", date);
            return true;
        } catch (DuplicateKeyException alreadyRun) {
            return false;
        }
    }

    List<Integer> importedTpexYears() {
        return jdbc.queryForList("SELECT report_year FROM tpex_delisting_archive_year", Integer.class);
    }

    List<Integer> importedTransferYears() {
        return jdbc.queryForList("SELECT report_year FROM tpex_listing_transfer_year", Integer.class);
    }

    void markTpexTransfersImported(int year) {
        jdbc.update("MERGE INTO tpex_listing_transfer_year (report_year) KEY (report_year) VALUES (?)", year);
    }

    void markTpexYearImported(int year) {
        jdbc.update("MERGE INTO tpex_delisting_archive_year (report_year) KEY (report_year) VALUES (?)", year);
    }

    List<String> heldCodes() {
        return jdbc.queryForList("SELECT code FROM holdings ORDER BY code", String.class);
    }

    List<MarketEvent> events(String code, LocalDate through) {
        return jdbc.query("""
                SELECT market, effective_date, kind, stock_name FROM market_event
                WHERE code = ? AND effective_date <= ?
                ORDER BY effective_date DESC, CASE kind WHEN 'RELISTED' THEN 0 WHEN 'DELISTED' THEN 1
                    WHEN 'RESUMED' THEN 2 ELSE 3 END
                """, (row, index) -> new MarketEvent(code, Market.valueOf(row.getString("market")),
                row.getObject("effective_date", LocalDate.class), MarketEvent.Kind.valueOf(row.getString("kind")),
                row.getString("stock_name")), code, through);
    }

    void save(MarketEvent event) {
        jdbc.update("""
                MERGE INTO market_event (code, market, effective_date, kind, stock_name)
                KEY (code, market, effective_date, kind) VALUES (?, ?, ?, ?, ?)
                """, event.code(), event.market().name(), event.date(), event.kind().name(), event.name());
    }

    @Transactional
    public void saveTpexTransfer(MarketEvent event) {
        save(event);
        jdbc.update("""
                DELETE FROM market_event WHERE code = ? AND market = 'TPEX'
                    AND effective_date = ? AND kind = 'DELISTED'
                """, event.code(), event.date());
        jdbc.update("""
                DELETE FROM market_final_close WHERE code = ? AND market = 'TPEX' AND effective_date = ?
                """, event.code(), event.date());
    }

    boolean transferredToTwse(String code, LocalDate date) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM market_event WHERE code = ? AND effective_date = ?
                    AND market = 'TWSE' AND kind = 'RELISTED'
                """, Integer.class, code, date) > 0;
    }

    Optional<Identity> identity(String code) {
        return jdbc.query("""
                SELECT issuer_id, listed_on FROM market_identity WHERE code = ?
                """, (row, index) -> new Identity(row.getString("issuer_id"),
                row.getObject("listed_on", LocalDate.class)), code).stream().findFirst();
    }

    void saveIdentity(String code, Identity identity) {
        jdbc.update("MERGE INTO market_identity (code, issuer_id, listed_on) KEY (code) VALUES (?, ?, ?)",
                code, identity.issuerId(), identity.listedOn());
    }

    Optional<FinalClose> finalClose(MarketEvent event) {
        return jdbc.query("""
                SELECT next_month, price_date, close_price FROM market_final_close
                WHERE code = ? AND market = ? AND effective_date = ?
                """, (row, index) -> new FinalClose(row.getObject("next_month", LocalDate.class),
                row.getObject("price_date", LocalDate.class), row.getBigDecimal("close_price")),
                event.code(), event.market().name(), event.date()).stream().findFirst();
    }

    Optional<LocalDate> finalTradingDayBeforeDelisting(String code, LocalDate buyDate) {
        return jdbc.query("""
                SELECT price_date FROM market_final_close
                WHERE code = ? AND price_date < ? AND effective_date > ?
                ORDER BY effective_date FETCH FIRST 1 ROW ONLY
                """, (row, index) -> row.getObject("price_date", LocalDate.class), code, buyDate, buyDate)
                .stream().findFirst();
    }

    void saveFinalClose(MarketEvent event, FinalClose result) {
        jdbc.update("""
                MERGE INTO market_final_close (code, market, effective_date, next_month, price_date, close_price)
                KEY (code, market, effective_date) VALUES (?, ?, ?, ?, ?, ?)
                """, event.code(), event.market().name(), event.date(), result.nextMonth(),
                result.priceDate(), result.close());
    }
}
