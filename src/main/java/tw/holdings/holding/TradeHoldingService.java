package tw.holdings.holding;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tw.holdings.quote.MarketStatusService;
import tw.holdings.quote.QuoteResult;
import tw.holdings.quote.QuoteService;
import tw.holdings.trade.InsufficientSharesException;
import tw.holdings.trade.Trade;
import tw.holdings.trade.TradeEntry;
import tw.holdings.trade.TradeHoldingNotFoundException;
import tw.holdings.trade.TradeLedger;
import tw.holdings.trade.TradeLedgerService;

@Service
class TradeHoldingService {
    private final HoldingRepository holdings;
    private final TradeLedgerService ledger;
    private final QuoteService quotes;
    private final MarketStatusService markets;
    private final TransactionTemplate transactions;
    private final TradeDateRules dates;

    TradeHoldingService(HoldingRepository holdings, TradeLedgerService ledger,
                        QuoteService quotes, TransactionTemplate transactions, TradeDateRules dates, MarketStatusService markets) {
        this.holdings = holdings;
        this.ledger = ledger;
        this.quotes = quotes;
        this.markets = markets;
        this.transactions = transactions;
        this.dates = dates;
    }

    List<TradeHoldingView> list() {
        return holdings.findAllByOrderByIdAsc().stream().map(row -> history(row).holding()).toList();
    }

    TradeHoldingView.History history(long id) {
        return history(holdings.findById(id).orElseThrow(() -> new HoldingNotFoundException(id)));
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
                Holding holding = holdings.saveAndFlush(Holding.managed(code, identity.name(), identity.market()));
                ledger.recordTrade(holding.id(), firstBuy);
                return holding.id();
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
        Holding holding = holdings.lockById(id).orElseThrow(() -> new HoldingNotFoundException(id));
        dates.validateNew(holding.code(), trade.tradeDate(), currentDate);
        validateBuy(holding.code(), trade);
        if (!holding.isV2Managed()) {
            if (!confirmV2) {
                throw HoldingVersionConflict.confirmationRequired();
            }
            holding.startV2Management();
        }
        try {
            ledger.recordTrade(id, trade);
        } catch (InsufficientSharesException exception) {
            if (trade.side() == Trade.Side.SELL && trade.tradeDate().isBefore(currentDate)) {
                throw exception.withHistoricalBuyHint();
            }
            throw exception;
        } catch (ArithmeticException exception) {
            throw new HoldingApiController.ApiValidationException("新增後的累計股數超過可儲存的範圍");
        }
    }

    DeleteTarget deleteTarget(long id) {
        Holding holding = holdings.findById(id).orElseThrow(() -> new HoldingNotFoundException(id));
        return DeleteTarget.from(holding);
    }

    record DeleteTarget(long id, String code, String name) {
        static DeleteTarget from(Holding holding) {
            return new DeleteTarget(holding.id(), holding.code(), holding.name());
        }
    }

    record TradeTarget(DeleteTarget holding, TradeEntry entry, boolean lastTrade, boolean v2Managed) {
    }

    enum TradeDeletion {
        TRADE_DELETED, HOLDING_DELETED, CONFIRM_AGAIN
    }

    TradeTarget tradeTarget(long id, long tradeId) {
        Holding holding = holdings.findById(id).orElseThrow(() -> new HoldingNotFoundException(id));
        var entries = ledger.entries(id);
        return new TradeTarget(DeleteTarget.from(holding), findTrade(entries, tradeId),
                entries.size() == 1, holding.isV2Managed());
    }

    @Transactional
    public void editTrade(long id, long tradeId, TradeEditForm form) {
        Holding holding = holdings.lockById(id).orElseThrow(() -> new HoldingNotFoundException(id));
        TradeEntry original = findTrade(ledger.entries(id), tradeId);
        Trade edited = form.applyTo(original.trade().side());
        if (edited.side() != Trade.Side.OPENING) {
            dates.validateEdit(holding.code(), original.trade().tradeDate(), edited.tradeDate());
            validateBuy(holding.code(), edited);
        }
        if (!holding.isV2Managed()) {
            if (!Boolean.TRUE.equals(form.confirmV2())) {
                throw HoldingVersionConflict.confirmationRequired();
            }
            holding.startV2Management();
        }

        try {
            ledger.updateTrade(id, original, edited, holding.code());
        } catch (ArithmeticException error) {
            throw new HoldingApiController.ApiValidationException("修改後的累計股數超過可儲存的範圍");
        }
    }

    @Transactional
    public TradeDeletion deleteTrade(long id, long tradeId, boolean confirmedLastTrade) {
        Holding holding = holdings.lockById(id).orElseThrow(() -> new HoldingNotFoundException(id));
        var entries = ledger.entries(id);
        findTrade(entries, tradeId);
        boolean lastTrade = entries.size() == 1;
        if (lastTrade != confirmedLastTrade) {
            return TradeDeletion.CONFIRM_AGAIN;
        }
        if (lastTrade) {
            deleteHolding(holding);
            return TradeDeletion.HOLDING_DELETED;
        }
        ledger.removeTrade(id, tradeId, holding.code());
        return TradeDeletion.TRADE_DELETED;
    }

    private TradeEntry findTrade(List<TradeEntry> entries, long tradeId) {
        return entries.stream().filter(entry -> entry.id() == tradeId).findFirst()
                .orElseThrow(() -> new HoldingTradeNotFoundException(tradeId));
    }

    @Transactional
    public void delete(long id) {
        Holding holding = holdings.lockById(id).orElseThrow(() -> new HoldingNotFoundException(id));
        deleteHolding(holding);
    }

    private void deleteHolding(Holding holding) {
        ledger.removeTrades(holding.id());
        holdings.delete(holding);
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
                throw new HoldingApiController.ApiValidationException(message);
            });
        }
    }

    private TradeHoldingView.History history(Holding holding) {
        var entries = ledger.entries(holding.id());
        var position = TradeLedger.replay(holding.code(), entries.stream().map(TradeEntry::trade).toList());
        var delisting = markets.delisting(holding.code()).orElse(null);
        QuoteResult quote = delisting == null ? quotes.forDisplay(holding.code(), holding.market()) : null;
        boolean found = quote != null && quote.status() == QuoteResult.Status.FOUND;
        var close = found ? quote.close() : null;
        String name = found ? quote.name() : holding.name();
        var market = found ? quote.market() : holding.market();
        if (delisting != null) {
            name = name == null ? delisting.name() : name;
            market = market == null ? delisting.market() : market;
        }
        var view = new TradeHoldingView(holding.id(), holding.code(), name, market, holding.isV2Managed(),
                position.shares(), position.remainingCost(), position.averageCost(), position.realizedPnl(),
                position.unrealizedPnl(holding.code(), close), close, found ? quote.priceDate() : null,
                position.realizedCost(), delisting);
        return new TradeHoldingView.History(view, TradeHoldingView.TradeDetail.from(entries));
    }
}
