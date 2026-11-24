package tw.holdings.quote.marketstatus;

import java.io.IOException;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tw.holdings.quote.Market;
import tw.holdings.quote.client.OfficialMarketClient;

@Service
class MarketStatusSyncService {
    private static final Logger LOGGER = LoggerFactory.getLogger(MarketStatusSyncService.class);
    private static final int FIRST_TPEX_YEAR = 1994;

    private final MarketStatusRepository repository;
    private final MarketStatusService status;
    private final OfficialMarketClient official;
    private final DelistedQuoteHistory history;

    MarketStatusSyncService(MarketStatusRepository repository, MarketStatusService status,
                            OfficialMarketClient official, DelistedQuoteHistory history) {
        this.repository = repository;
        this.status = status;
        this.official = official;
        this.history = history;
    }

    void sync(LocalDate day) {
        boolean daily = repository.claimDay(day);
        try {
            if (daily) {
                loadEvents(day.getYear());
            }
            var held = new HashSet<>(repository.heldCodes());
            var backfilled = loadTpexYears(day, held, daily);
            if (daily) {
                refreshListings(day);
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
                        () -> official.transfersToTwse(reportYear),
                        event -> repository.saveTpexTransfer(MarketEvent.fromOfficial(event)));
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
                    event -> recordTpexDelisting(MarketEvent.fromOfficial(event), held, codes));
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
        importRows("TWSE delistings", () -> official.delistings(Market.TWSE, year), this::saveOfficialEvent);
        for (Market market : Market.values()) {
            importRows("halts " + market, () -> official.halts(market), this::saveOfficialEvent);
            importRows("stops " + market, () -> official.stops(market), this::saveOfficialEvent);
        }
    }

    private void saveOfficialEvent(OfficialMarketClient.Event event) {
        repository.save(MarketEvent.fromOfficial(event));
    }

    void reviewPending(LocalDate day) {
        var pending=status.claimListingReviews();
        if(pending.isEmpty())return;
        try {
            refreshListings(day);
            pending.stream().filter(status::reviewPending).forEach(status::requestReview);
        } catch(InterruptedException interrupted) {
            pending.forEach(status::requestReview);
            Thread.currentThread().interrupt();
        }
    }

    private boolean refreshListings(LocalDate day) throws InterruptedException {
        var rosters=new java.util.EnumMap<Market,List<OfficialMarketClient.Listing>>(Market.class);
        for(var market:Market.values()) {
            try { rosters.put(market,official.listings(market)); }
            catch(IOException | RuntimeException unavailable) {
                LOGGER.warn("Official listing roster unavailable: market={}",market);
            }
        }
        if(rosters.size()!=Market.values().length)return false;
        new MarketListingReconciler(repository,status).reconcile(rosters,day);
        return true;
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

    @FunctionalInterface
    private interface OfficialRows<T> {
        List<T> read() throws IOException, InterruptedException;
    }
}
