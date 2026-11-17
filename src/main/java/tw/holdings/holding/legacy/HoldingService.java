package tw.holdings.holding.legacy;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tw.holdings.holding.error.DuplicateHoldingException;
import tw.holdings.holding.error.HoldingNotFoundException;
import tw.holdings.holding.error.HoldingVersionConflict;
import tw.holdings.holding.error.StockLookupException;
import tw.holdings.holding.persistence.HoldingStore;
import tw.holdings.profit.PortfolioSummary;
import tw.holdings.profit.ProfitCalculator;
import tw.holdings.quote.QuoteResult;
import tw.holdings.quote.QuoteService;
import tw.holdings.quote.marketstatus.MarketStatusService;
import tw.holdings.trade.TradeLedgerService;

@Service
class HoldingService {
    private final HoldingStore holdings;
    private final QuoteService quotes;
    private final MarketStatusService markets;

    private final TradeLedgerService ledger;
    private final TransactionTemplate transactions;

    HoldingService(HoldingStore holdings, QuoteService quotes,
                   TradeLedgerService ledger, TransactionTemplate transactions, MarketStatusService markets) {
        this.holdings = holdings;
        this.quotes = quotes;
        this.markets = markets;
        this.ledger = ledger;
        this.transactions = transactions;
    }

    HoldingPortfolio portfolio() {
        List<HoldingQuoteView> holdings = list();
        PortfolioSummary summary = PortfolioSummary.from(holdings.stream()
                .map(HoldingQuoteView::profit)
                .toList());
        return new HoldingPortfolio(holdings, summary);
    }

    List<HoldingQuoteView> list() {
        return holdings.listLegacy().stream()
                .map(HoldingService::details)
                .map(this::withQuote)
                .toList();
    }

    HoldingQuoteView withQuote(HoldingDetails holding) {
        var delisting = markets.delisting(holding.code()).orElse(null);
        if (delisting != null) {
            String name = holding.name() == null ? delisting.name() : holding.name();
            var market = holding.market() == null ? delisting.market() : holding.market();
            var profit = ProfitCalculator.calculate(holding.code(), holding.lots(), holding.buyPrice(), null);
            return new HoldingQuoteView(holding.id(), holding.code(), holding.lots(),
                    holding.buyPrice(), name, market, null, null, profit, delisting);
        }
        QuoteResult quote = quotes.forDisplay(holding.code(), holding.market());
        boolean found = quote.status() == QuoteResult.Status.FOUND;
        String name = found ? quote.name() : holding.name();
        var market = found ? quote.market() : holding.market();
        if (found && (holding.name() == null || holding.market() == null)) {
            holdings.fillMissingIdentity(holding.id(), name, market);
        }
        var profit = ProfitCalculator.calculate(holding.code(), holding.lots(), holding.buyPrice(), quote.close());
        return new HoldingQuoteView(holding.id(), holding.code(), holding.lots(),
                holding.buyPrice(), name, market, quote.close(), quote.priceDate(), profit, null);
    }

    HoldingDetails get(long id) {
        return details(legacy(holdings.find(id).orElseThrow(() -> new HoldingNotFoundException(id))));
    }

    HoldingDetails create(String code, int lots, BigDecimal buyPrice) {
        String normalizedCode = code.strip();
        markets.delisting(normalizedCode).ifPresent(delisting -> {
            throw new StockLookupException(QuoteResult.Status.NOT_FOUND, normalizedCode + " " + delisting.label());
        });
        if (holdings.existsByCode(normalizedCode)) {
            throw new DuplicateHoldingException(normalizedCode, null, holdings.existsByCodeAndV2ManagedTrue(normalizedCode));
        }
        QuoteResult quote = quotes.lookup(normalizedCode, null);
        if (quote.status() != QuoteResult.Status.FOUND) {
            throw new StockLookupException(quote.status());
        }
        try {
            return transactions.execute(status -> {
                var holding = holdings.createLegacy(normalizedCode, lots, buyPrice, quote.name(), quote.market());
                ledger.replaceLegacyOpening(holding.id(), lots, buyPrice);
                return details(holding);
            });
        } catch (DataIntegrityViolationException exception) {
            if (exception.getMostSpecificCause() instanceof SQLException sqlException
                    && "23505".equals(sqlException.getSQLState())) {
                throw new DuplicateHoldingException(normalizedCode, exception, holdings.existsByCodeAndV2ManagedTrue(normalizedCode));
            }
            throw exception;
        }
    }

    @Transactional
    HoldingDetails update(long id, int lots, BigDecimal buyPrice) {
        var locked = lockLegacy(id);
        locked.updateLegacyTerms(lots, buyPrice);
        ledger.replaceLegacyOpening(id, lots, buyPrice);
        return details(locked.snapshot());
    }

    @Transactional
    void delete(long id) {
        var locked = lockLegacy(id);
        ledger.removeLegacyOpening(id);
        holdings.delete(locked);
    }

    private HoldingStore.LockedHolding lockLegacy(long id) {
        var locked = holdings.lock(id).orElseThrow(() -> new HoldingNotFoundException(id));
        legacy(locked.snapshot());
        return locked;
    }

    private HoldingStore.Snapshot legacy(HoldingStore.Snapshot holding) {
        if (holding.v2Managed()) {
            throw HoldingVersionConflict.managedByV2();
        }
        return holding;
    }

    private static HoldingDetails details(HoldingStore.Snapshot holding) {
        return new HoldingDetails(holding.id(), holding.code(), holding.lots(), holding.buyPrice(),
                holding.name(), holding.market());
    }
}
