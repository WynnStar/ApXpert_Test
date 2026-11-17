package tw.holdings.holding;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tw.holdings.profit.PortfolioSummary;
import tw.holdings.profit.ProfitCalculator;
import tw.holdings.quote.QuoteResult;
import tw.holdings.quote.QuoteService;

@Service
class HoldingService {
    private final HoldingRepository repository;
    private final QuoteService quotes;

    HoldingService(HoldingRepository repository, QuoteService quotes) {
        this.repository = repository;
        this.quotes = quotes;
    }

    HoldingPortfolio portfolio() {
        List<HoldingQuoteView> holdings = list();
        PortfolioSummary summary = PortfolioSummary.from(holdings.stream()
                .map(HoldingQuoteView::profit)
                .toList());
        return new HoldingPortfolio(holdings, summary);
    }

    List<HoldingQuoteView> list() {
        return repository.findAllByOrderByIdAsc().stream()
                .map(Holding::toDetails)
                .map(this::withQuote)
                .toList();
    }

    private HoldingQuoteView withQuote(HoldingDetails holding) {
        QuoteResult quote = quotes.lookup(holding.code(), holding.market());
        boolean found = quote.status() == QuoteResult.Status.FOUND;
        String name = found ? quote.name() : holding.name();
        var market = found ? quote.market() : holding.market();
        if (found && (holding.name() == null || holding.market() == null)) {
            repository.fillMissingIdentity(holding.id(), name, market);
        }
        var profit = ProfitCalculator.calculate(holding.code(), holding.lots(), holding.buyPrice(), quote.close());
        return new HoldingQuoteView(holding.id(), holding.code(), holding.lots(),
                holding.buyPrice(), name, market, quote.close(), quote.priceDate(), profit);
    }

    HoldingDetails get(long id) {
        return find(id).toDetails();
    }

    void create(String code, int lots, BigDecimal buyPrice) {
        String normalizedCode = code.strip();
        if (repository.existsByCode(normalizedCode)) {
            throw new DuplicateHoldingException(normalizedCode, null);
        }
        QuoteResult quote = quotes.lookup(normalizedCode, null);
        if (quote.status() != QuoteResult.Status.FOUND) {
            throw new StockLookupException(quote.status());
        }
        try {
            repository.saveAndFlush(new Holding(normalizedCode, lots, buyPrice, quote.name(), quote.market()));
        } catch (DataIntegrityViolationException exception) {
            if (exception.getMostSpecificCause() instanceof SQLException sqlException
                    && "23505".equals(sqlException.getSQLState())) {
                throw new DuplicateHoldingException(normalizedCode, exception);
            }
            throw exception;
        }
    }

    @Transactional
    void update(long id, int lots, BigDecimal buyPrice) {
        find(id).update(lots, buyPrice);
    }

    @Transactional
    void delete(long id) {
        repository.delete(find(id));
    }

    private Holding find(long id) {
        return repository.findById(id).orElseThrow(() -> new HoldingNotFoundException(id));
    }
}
