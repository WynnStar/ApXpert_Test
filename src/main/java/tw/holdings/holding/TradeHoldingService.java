package tw.holdings.holding;

import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tw.holdings.quote.QuoteResult;
import tw.holdings.quote.QuoteService;
import tw.holdings.trade.InsufficientSharesException;
import tw.holdings.trade.Trade;
import tw.holdings.trade.TradeLedger;
import tw.holdings.trade.TradeLedgerService;

@Service
class TradeHoldingService {
    private final HoldingRepository holdings;
    private final TradeLedgerService ledger;
    private final QuoteService quotes;
    private final TransactionTemplate transactions;
    private final Clock clock;

    TradeHoldingService(HoldingRepository holdings, TradeLedgerService ledger,
                        QuoteService quotes, TransactionTemplate transactions, Clock clock) {
        this.holdings = holdings;
        this.ledger = ledger;
        this.quotes = quotes;
        this.transactions = transactions;
        this.clock = clock;
    }

    List<TradeHoldingView> list() {
        return holdings.findAllByOrderByIdAsc().stream().map(row -> history(row).holding()).toList();
    }

    TradeHoldingView.History history(long id) {
        return history(holdings.findById(id).orElseThrow(() -> new HoldingNotFoundException(id)));
    }

    LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Taipei"));
    }

    long create(String code, Trade firstBuy) {
        validateTradeDate(firstBuy, today());
        if (holdings.existsByCode(code)) {
            throw new DuplicateHoldingException(code, null);
        }
        QuoteResult identity = quotes.lookup(code, null);
        if (identity.status() != QuoteResult.Status.FOUND) {
            throw new StockLookupException(identity.status());
        }

        try {
            return transactions.execute(status -> {
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
        LocalDate currentDate = today();
        validateTradeDate(trade, currentDate);
        Holding holding = holdings.lockById(id).orElseThrow(() -> new HoldingNotFoundException(id));
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
        }
    }

    private void validateTradeDate(Trade trade, LocalDate currentDate) {
        if (trade.tradeDate().isAfter(currentDate)) {
            throw new HoldingApiController.ApiValidationException("交易日期不能晚於今天");
        }
    }

    private TradeHoldingView.History history(Holding holding) {
        var trades = ledger.trades(holding.id());
        var position = TradeLedger.replay(holding.code(), trades);
        QuoteResult quote = quotes.forDisplay(holding.code(), holding.market());
        boolean found = quote != null && quote.status() == QuoteResult.Status.FOUND;
        var close = found ? quote.close() : null;
        var view = new TradeHoldingView(holding.id(), holding.code(),
                found ? quote.name() : holding.name(), found ? quote.market() : holding.market(), holding.isV2Managed(),
                position.shares(), position.remainingCost(), position.averageCost(), position.realizedPnl(),
                position.unrealizedPnl(holding.code(), close), close, found ? quote.priceDate() : null);
        return new TradeHoldingView.History(view, trades.stream().map(TradeHoldingView.TradeDetail::from).toList());
    }
}
