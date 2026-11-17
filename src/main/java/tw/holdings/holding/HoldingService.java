package tw.holdings.holding;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tw.holdings.profit.PortfolioSummary;
import tw.holdings.profit.ProfitCalculator;
import tw.holdings.quote.MarketStatusService;
import tw.holdings.quote.QuoteResult;
import tw.holdings.quote.QuoteService;
import tw.holdings.trade.TradeLedgerService;

@Service
class HoldingService {
    private final HoldingRepository repository;
    private final QuoteService quotes;
    private final MarketStatusService markets;

    private final TradeLedgerService ledger;
    private final TransactionTemplate transactions;

    HoldingService(HoldingRepository repository, QuoteService quotes,
                   TradeLedgerService ledger, TransactionTemplate transactions, MarketStatusService markets) {
        this.repository = repository;
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
        return repository.findAllByV2ManagedFalseOrderByIdAsc().stream()
                .map(Holding::toDetails)
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
            repository.fillMissingIdentity(holding.id(), name, market);
        }
        var profit = ProfitCalculator.calculate(holding.code(), holding.lots(), holding.buyPrice(), quote.close());
        return new HoldingQuoteView(holding.id(), holding.code(), holding.lots(),
                holding.buyPrice(), name, market, quote.close(), quote.priceDate(), profit, null);
    }

    HoldingDetails get(long id) {
        return legacy(repository.findById(id).orElseThrow(() -> new HoldingNotFoundException(id))).toDetails();
    }

    HoldingDetails create(String code, int lots, BigDecimal buyPrice) {
        String normalizedCode = code.strip();
        markets.delisting(normalizedCode).ifPresent(delisting -> {
            throw new StockLookupException(QuoteResult.Status.NOT_FOUND, normalizedCode + " " + delisting.label());
        });
        if (repository.existsByCode(normalizedCode)) {
            throw new DuplicateHoldingException(normalizedCode, null, repository.existsByCodeAndV2ManagedTrue(normalizedCode));
        }
        QuoteResult quote = quotes.lookup(normalizedCode, null);
        if (quote.status() != QuoteResult.Status.FOUND) {
            throw new StockLookupException(quote.status());
        }
        try {
            return transactions.execute(status -> {
                Holding holding = repository.saveAndFlush(new Holding(
                        normalizedCode, lots, buyPrice, quote.name(), quote.market()));
                ledger.replaceLegacyOpening(holding.id(), lots, buyPrice);
                return holding.toDetails();
            });
        } catch (DataIntegrityViolationException exception) {
            if (exception.getMostSpecificCause() instanceof SQLException sqlException
                    && "23505".equals(sqlException.getSQLState())) {
                throw new DuplicateHoldingException(normalizedCode, exception, repository.existsByCodeAndV2ManagedTrue(normalizedCode));
            }
            throw exception;
        }
    }

    @Transactional
    HoldingDetails update(long id, int lots, BigDecimal buyPrice) {
        Holding holding = lockLegacy(id);
        holding.update(lots, buyPrice);
        ledger.replaceLegacyOpening(id, lots, buyPrice);
        return holding.toDetails();
    }

    @Transactional
    void delete(long id) {
        Holding holding = lockLegacy(id);
        ledger.removeLegacyOpening(id);
        repository.delete(holding);
    }

    private Holding lockLegacy(long id) {
        return legacy(repository.lockById(id).orElseThrow(() -> new HoldingNotFoundException(id)));
    }

    private Holding legacy(Holding holding) {
        if (holding.isV2Managed()) {
            throw HoldingVersionConflict.managedByV2();
        }
        return holding;
    }
}
