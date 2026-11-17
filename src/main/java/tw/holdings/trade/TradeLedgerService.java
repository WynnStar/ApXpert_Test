package tw.holdings.trade;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TradeLedgerService {
    private final TradeRepository repository;

    TradeLedgerService(TradeRepository repository) {
        this.repository = repository;
    }

    public List<Trade> trades(long holdingId) {
        return repository.findByHolding(holdingId);
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

    @Transactional
    public TradePosition recordTrade(long holdingId, Trade trade) {
        String code = repository.lockHoldingCode(holdingId)
                .orElseThrow(() -> new TradeHoldingNotFoundException(holdingId));
        // 回放失敗會回滾新增的交易，持股列鎖則讓同一檔不能同時驗證兩筆賣出。
        repository.insert(holdingId, trade);
        return TradeLedger.replay(code, repository.findByHolding(holdingId));
    }
}
