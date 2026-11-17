package tw.holdings.trade;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TradeLedgerService {
    private final TradeRepository repository;

    TradeLedgerService(TradeRepository repository) {
        this.repository = repository;
    }

    private List<Trade> trades(long holdingId) {
        return entries(holdingId).stream().map(TradeEntry::trade).toList();
    }

    public List<TradeEntry> entries(long holdingId) {
        return repository.findByHolding(holdingId);
    }

    public enum Direction {
        UP(-1), DOWN(1);

        private final int offset;

        Direction(int offset) {
            this.offset = offset;
        }
    }

    @Transactional
    public void move(long holdingId, long tradeId, Direction direction) {
        String code = repository.lockHoldingCode(holdingId)
                .orElseThrow(() -> new TradeHoldingNotFoundException(holdingId));
        var entries = entries(holdingId);
        int index = IntStream.range(0, entries.size())
                .filter(i -> entries.get(i).id() == tradeId)
                .findFirst().orElseThrow(TradeOrderException::new);
        int neighbor = index + direction.offset;
        TradeEntry entry = entries.get(index);
        if (neighbor < 0 || neighbor >= entries.size() || !entry.sameDayAs(entries.get(neighbor))) {
            throw new TradeOrderException();
        }

        repository.swapOrder(holdingId, entry, entries.get(neighbor));
        // 與新增交易共用回放規則。任何一筆賣出超過當時股數，順序變更會隨交易回滾。
        TradeLedger.replay(code, trades(holdingId));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void replaceLegacyOpening(long holdingId, int lots, BigDecimal buyPrice) {
        removeLegacyOpening(holdingId);
        repository.insert(holdingId, new Trade(Trade.Side.OPENING, null, lots * 1_000L, buyPrice));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void removeLegacyOpening(long holdingId) {
        repository.removeOpening(holdingId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void removeTrades(long holdingId) {
        repository.deleteByHolding(holdingId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void updateTrade(long holdingId, TradeEntry original, Trade edited, String code) {
        repository.updateTrade(holdingId, original, edited);
        TradeLedger.replay(code, trades(holdingId));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void removeTrade(long holdingId, long tradeId, String code) {
        repository.deleteTrade(holdingId, tradeId);
        // 若剩餘交易存在超賣，保留原始紀錄。
        TradeLedger.replay(code, trades(holdingId));
    }

    @Transactional
    public TradePosition recordTrade(long holdingId, Trade trade) {
        String code = repository.lockHoldingCode(holdingId)
                .orElseThrow(() -> new TradeHoldingNotFoundException(holdingId));
        // 回放失敗會回滾新增的交易，持股列鎖則讓同一檔不能同時驗證兩筆賣出。
        repository.insert(holdingId, trade);
        return TradeLedger.replay(code, trades(holdingId));
    }
}
