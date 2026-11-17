package tw.holdings.holding.v2;

import java.util.List;

import org.springframework.stereotype.Service;
import tw.holdings.holding.error.HoldingNotFoundException;
import tw.holdings.holding.error.HoldingTradeNotFoundException;
import tw.holdings.holding.persistence.HoldingStore;
import tw.holdings.quote.QuoteResult;
import tw.holdings.quote.QuoteService;
import tw.holdings.quote.marketstatus.MarketStatusService;
import tw.holdings.trade.TradeEntry;
import tw.holdings.trade.TradeLedger;
import tw.holdings.trade.TradeLedgerService;

@Service
class TradeHoldingQueryService {
    private final HoldingStore holdings;
    private final TradeLedgerService ledger;
    private final QuoteService quotes;
    private final MarketStatusService markets;

    TradeHoldingQueryService(HoldingStore holdings, TradeLedgerService ledger,
                             QuoteService quotes, MarketStatusService markets) {
        this.holdings = holdings;
        this.ledger = ledger;
        this.quotes = quotes;
        this.markets = markets;
    }

    List<TradeHoldingView> list() {
        return holdings.listAll().stream()
                .map(holding -> holdingView(holding, ledger.entries(holding.id())))
                .toList();
    }

    TradePortfolioSummary summary() {
        return TradePortfolioSummary.from(list());
    }

    TradeHoldingView.History history(long id) {
        HoldingStore.Snapshot holding = findHolding(id);
        var entries = ledger.entries(id);
        return new TradeHoldingView.History(holdingView(holding, entries), TradeHoldingView.TradeDetail.from(entries));
    }

    DeleteTarget deleteTarget(long id) {
        return DeleteTarget.from(findHolding(id));
    }

    TradeTarget tradeTarget(long id, long tradeId) {
        HoldingStore.Snapshot holding = findHolding(id);
        var entries = ledger.entries(id);
        var entry = TradeEntry.findById(entries, tradeId)
                .orElseThrow(() -> new HoldingTradeNotFoundException(tradeId));
        return new TradeTarget(DeleteTarget.from(holding), entry, entries.size() == 1, holding.v2Managed());
    }

    private HoldingStore.Snapshot findHolding(long id) {
        return holdings.find(id).orElseThrow(() -> new HoldingNotFoundException(id));
    }

    private TradeHoldingView holdingView(HoldingStore.Snapshot holding, List<TradeEntry> entries) {
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
        return new TradeHoldingView(holding.id(), holding.code(), name, market, holding.v2Managed(),
                position.shares(), position.remainingCost(), position.averageCost(), position.realizedPnl(),
                position.unrealizedPnl(holding.code(), close), close, found ? quote.priceDate() : null,
                position.realizedCost(), delisting);
    }

    record DeleteTarget(long id, String code, String name) {
        private static DeleteTarget from(HoldingStore.Snapshot holding) {
            return new DeleteTarget(holding.id(), holding.code(), holding.name());
        }
    }

    record TradeTarget(DeleteTarget holding, TradeEntry entry, boolean lastTrade, boolean v2Managed) {}
}
