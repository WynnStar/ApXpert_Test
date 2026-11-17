package tw.holdings.holding.v2;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tw.holdings.holding.error.DuplicateHoldingException;
import tw.holdings.holding.error.HoldingNotFoundException;
import tw.holdings.holding.error.HoldingValidationException;
import tw.holdings.holding.error.HoldingVersionConflict;
import tw.holdings.holding.error.StockLookupException;
import tw.holdings.holding.persistence.HoldingStore;
import tw.holdings.quote.QuoteResult;
import tw.holdings.quote.QuoteService;
import tw.holdings.quote.marketstatus.MarketStatusService;
import tw.holdings.trade.InsufficientSharesException;
import tw.holdings.trade.Trade;
import tw.holdings.trade.TradeEntry;
import tw.holdings.trade.TradeHoldingNotFoundException;
import tw.holdings.trade.TradeLedgerService;

@Service
class TradeHoldingService {
    private final HoldingStore holdings;
    private final TradeLedgerService ledger;
    private final QuoteService quotes;
    private final MarketStatusService markets;
    private final TransactionTemplate transactions;
    private final TradeDateRules dates;

    TradeHoldingService(HoldingStore holdings, TradeLedgerService ledger,
                        QuoteService quotes, TransactionTemplate transactions, TradeDateRules dates, MarketStatusService markets) {
        this.holdings = holdings;
        this.ledger = ledger;
        this.quotes = quotes;
        this.markets = markets;
        this.transactions = transactions;
        this.dates = dates;
    }

    long create(String code, Trade firstBuy) {
        dates.validateNew(code, firstBuy.tradeDate(), dates.today());
        validateBuy(code, firstBuy);
        if (holdings.existsByCode(code)) {
            throw new DuplicateHoldingException(code, null);
        }
        QuoteResult identity = markets.delisting(code)
                .map(delisting -> new QuoteResult(QuoteResult.Status.FOUND,
                        delisting.name(), delisting.market(), null, null))
                .orElseGet(() -> quotes.lookup(code, null));
        if (identity.status() != QuoteResult.Status.FOUND) {
            throw new StockLookupException(identity.status());
        }

        try {
            return transactions.execute(status -> {
                dates.validateNew(code, firstBuy.tradeDate(), dates.today());
                validateBuy(code, firstBuy);
                long id = holdings.createV2(code, identity.name(), identity.market());
                ledger.recordTrade(id, firstBuy);
                return id;
            });
        } catch (DataIntegrityViolationException exception) {
            if (exception.getMostSpecificCause() instanceof SQLException sql
                    && "23505".equals(sql.getSQLState())) {
                throw new DuplicateHoldingException(code, exception);
            }
            throw exception;
        }
    }

    @Transactional
    public void append(long id, Trade trade, boolean confirmV2) {
        LocalDate currentDate = dates.today();
        var locked = holdings.lock(id).orElseThrow(() -> new HoldingNotFoundException(id));
        var holding = locked.snapshot();
        dates.validateNew(holding.code(), trade.tradeDate(), currentDate);
        validateBuy(holding.code(), trade);
        if (!holding.v2Managed()) {
            if (!confirmV2) {
                throw HoldingVersionConflict.confirmationRequired();
            }
            locked.startV2Management();
        }
        try {
            ledger.recordTrade(id, trade);
        } catch (InsufficientSharesException exception) {
            if (trade.side() == Trade.Side.SELL && trade.tradeDate().isBefore(currentDate)) {
                throw exception.withHistoricalBuyHint();
            }
            throw exception;
        } catch (ArithmeticException exception) {
            throw new HoldingValidationException("新增後的累計股數超過可儲存的範圍");
        }
    }

    enum TradeDeletion {
        TRADE_DELETED, HOLDING_DELETED, CONFIRM_AGAIN
    }

    @Transactional
    public void editTrade(long id, long tradeId, TradeEditForm form) {
        var locked = holdings.lock(id).orElseThrow(() -> new HoldingNotFoundException(id));
        var holding = locked.snapshot();
        TradeEntry original = findTrade(ledger.entries(id), tradeId);
        Trade edited = form.applyTo(original.trade().side());
        if (edited.side() != Trade.Side.OPENING) {
            dates.validateEdit(holding.code(), original.trade().tradeDate(), edited.tradeDate());
            validateBuy(holding.code(), edited);
        }
        if (!holding.v2Managed()) {
            if (!Boolean.TRUE.equals(form.confirmV2())) {
                throw HoldingVersionConflict.confirmationRequired();
            }
            locked.startV2Management();
        }

        try {
            ledger.updateTrade(id, original, edited, holding.code());
        } catch (ArithmeticException error) {
            throw new HoldingValidationException("修改後的累計股數超過可儲存的範圍");
        }
    }

    @Transactional
    public TradeDeletion deleteTrade(long id, long tradeId, boolean confirmedLastTrade) {
        var locked = holdings.lock(id).orElseThrow(() -> new HoldingNotFoundException(id));
        var entries = ledger.entries(id);
        findTrade(entries, tradeId);
        boolean lastTrade = entries.size() == 1;
        if (lastTrade != confirmedLastTrade) {
            return TradeDeletion.CONFIRM_AGAIN;
        }
        if (lastTrade) {
            deleteHolding(id, locked);
            return TradeDeletion.HOLDING_DELETED;
        }
        ledger.removeTrade(id, tradeId, locked.snapshot().code());
        return TradeDeletion.TRADE_DELETED;
    }

    private TradeEntry findTrade(List<TradeEntry> entries, long tradeId) {
        return TradeEntry.findById(entries, tradeId)
                .orElseThrow(() -> new HoldingTradeNotFoundException(tradeId));
    }

    @Transactional
    public void delete(long id) {
        var locked = holdings.lock(id).orElseThrow(() -> new HoldingNotFoundException(id));
        deleteHolding(id, locked);
    }

    private void deleteHolding(long id, HoldingStore.LockedHolding locked) {
        ledger.removeTrades(id);
        holdings.delete(locked);
    }

    void move(long id, long tradeId, TradeLedgerService.Direction direction) {
        try {
            ledger.move(id, tradeId, direction);
        } catch (TradeHoldingNotFoundException error) {
            throw new HoldingNotFoundException(id);
        }
    }

    private void validateBuy(String code, Trade trade) {
        if (trade.side() == Trade.Side.BUY) {
            markets.buyRestriction(code, trade.tradeDate()).ifPresent(message -> {
                throw new HoldingValidationException(message);
            });
        }
    }
}
