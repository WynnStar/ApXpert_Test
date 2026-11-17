package tw.holdings.quote;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "quotes.market-status-sync", matchIfMissing = true)
class MarketStatusBatch {
    private static final Logger LOGGER = LoggerFactory.getLogger(MarketStatusBatch.class);
    private static final LocalTime RUN_AT = LocalTime.of(8, 0);
    private static final int FIRST_TPEX_YEAR = 1994;

    private final MarketStatusRepository repository;
    private final MarketStatusService status;
    private final OfficialMarketClient official;
    private final DelistedQuoteHistory history;
    private final Clock clock;
    private final ExecutorService quoteWorkers;

    MarketStatusBatch(MarketStatusRepository repository, MarketStatusService status, OfficialMarketClient official,
                      DelistedQuoteHistory history, Clock clock, ExecutorService quoteWorkers) {
        this.repository = repository;
        this.status = status;
        this.official = official;
        this.history = history;
        this.clock = clock;
        this.quoteWorkers = quoteWorkers;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(cron = "0 0 8 * * *", zone = "Asia/Taipei")
    void enqueue() {
        if (!now().toLocalTime().isBefore(RUN_AT)) {
            quoteWorkers.execute(this::runIfDue);
        }
    }

    synchronized void runIfDue() {
        var now = now();
        LocalDate day = now.toLocalDate();
        if (now.toLocalTime().isBefore(RUN_AT)) {
            return;
        }
        boolean daily = repository.claimDay(day);
        try {
            if (daily) {
                loadEvents(day.getYear());
            }
            var held = new HashSet<>(repository.heldCodes());
            var backfilled = loadTpexYears(day, held, daily);
            if (daily) {
                for (Market market : Market.values()) {
                    importRows("active listings " + market, () -> official.listings(market),
                            listing -> reconcileListing(listing, day));
                }
                for (String code : held.stream().sorted().toList()) {
                    if (!backfilled.contains(code)) {
                        refreshHistory(code, day);
                    }
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private Set<String> loadTpexYears(LocalDate day, Set<String> held, boolean daily)
            throws InterruptedException {
        var imported = new HashSet<>(repository.importedTpexYears());
        var transferYears = new HashSet<>(repository.importedTransferYears());
        var backfilled = new HashSet<String>();
        for (int year = day.getYear(); year >= FIRST_TPEX_YEAR; year--) {
            int reportYear = year;
            boolean recent = year >= day.getYear() - 1;
            if (!transferYears.contains(year) || (recent && daily)) {
                boolean transfersLoaded = importRows("TPEx transfers to TWSE " + year,
                        () -> official.transfersToTwse(reportYear), repository::saveTpexTransfer);
                if (!transfersLoaded) {
                    continue;
                }
                repository.markTpexTransfersImported(year);
            }
            if (recent ? !daily : imported.contains(year)) {
                continue;
            }
            var codes = new HashSet<String>();
            boolean success = importRows("TPEx delistings " + year,
                    () -> official.delistings(Market.TPEX, reportYear),
                    event -> recordTpexDelisting(event, held, codes));
            if (success && !recent) {
                for (String code : codes.stream().sorted().toList()) {
                    if (backfilled.add(code)) {
                        refreshHistory(code, day);
                    }
                }
                repository.markTpexYearImported(year);
            }
        }
        return backfilled;
    }

    private void recordTpexDelisting(MarketEvent event, Set<String> held, Set<String> backfill) {
        if (repository.transferredToTwse(event.code(), event.date())) {
            return;
        }
        repository.save(event);
        if (held.contains(event.code())) {
            backfill.add(event.code());
        }
    }

    private void refreshHistory(String code, LocalDate day) throws InterruptedException {
        var event = status.listingEvent(code, day).orElse(null);
        if (event != null && event.kind() == MarketEvent.Kind.DELISTED) {
            history.refresh(event);
        }
    }

    private void loadEvents(int year) throws InterruptedException {
        importRows("TWSE delistings", () -> official.delistings(Market.TWSE, year), repository::save);
        for (Market market : Market.values()) {
            importRows("halts " + market, () -> official.halts(market), repository::save);
            importRows("stops " + market, () -> official.stops(market), repository::save);
        }
    }

    private void reconcileListing(OfficialMarketClient.Listing listing, LocalDate day) {
        if (listing.date().isAfter(day)) {
            return;
        }
        var previous = repository.identity(listing.code()).orElse(null);
        var latest = status.listingEvent(listing.code(), day).orElse(null);
        if (latest != null && latest.kind() == MarketEvent.Kind.DELISTED) {
            if (previous != null && previous.issuerId().equals(listing.issuer())
                    && previous.listedOn().isBefore(latest.date()) && !listing.date().isBefore(latest.date())) {
                repository.save(new MarketEvent(listing.code(), listing.market(), listing.date(),
                        MarketEvent.Kind.RELISTED, listing.name()));
            }
            return;
        }
        if (previous == null) {
            repository.saveIdentity(listing.code(), new MarketStatusRepository.Identity(listing.issuer(), listing.date()));
        }
    }

    private <T> boolean importRows(String label, OfficialRows<T> load, Consumer<T> save) throws InterruptedException {
        List<T> rows;
        try {
            rows = load.read();
        } catch (IOException | RuntimeException error) {
            LOGGER.warn("Market status feed unavailable: {}, reason={}", label, error.getClass().getSimpleName());
            return false;
        }
        rows.forEach(save);
        return true;
    }

    private ZonedDateTime now() {
        return clock.instant().atZone(QuoteRefreshPolicy.TAIPEI);
    }

    @FunctionalInterface
    private interface OfficialRows<T> {
        List<T> read() throws IOException, InterruptedException;
    }
}
