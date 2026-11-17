package tw.holdings.holding.persistence;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tw.holdings.quote.Market;

@Component
public class HoldingStore {
    private final HoldingRepository repository;

    HoldingStore(HoldingRepository repository) {
        this.repository = repository;
    }

    public List<Snapshot> listLegacy() {
        return repository.findAllByV2ManagedFalseOrderByIdAsc().stream().map(Holding::snapshot).toList();
    }

    public List<Snapshot> listAll() {
        return repository.findAllByOrderByIdAsc().stream().map(Holding::snapshot).toList();
    }

    public Optional<Snapshot> find(long id) {
        return repository.findById(id).map(Holding::snapshot);
    }

    public boolean existsByCode(String code) {
        return repository.existsByCode(code);
    }

    public boolean existsByCodeAndV2ManagedTrue(String code) {
        return repository.existsByCodeAndV2ManagedTrue(code);
    }

    public Snapshot createLegacy(String code, int lots, BigDecimal buyPrice, String name, Market market) {
        return repository.saveAndFlush(new Holding(code, lots, buyPrice, name, market)).snapshot();
    }

    public long createV2(String code, String name, Market market) {
        return repository.saveAndFlush(Holding.managed(code, name, market)).id();
    }

    public void fillMissingIdentity(long id, String name, Market market) {
        repository.fillMissingIdentity(id, name, market);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<LockedHolding> lock(long id) {
        return repository.lockById(id).map(LockedHolding::new);
    }

    public void delete(LockedHolding locked) {
        repository.delete(locked.holding);
    }

    public record Snapshot(long id, String code, Integer lots, BigDecimal buyPrice,
                           String name, Market market, boolean v2Managed) {
    }

    /** A locked row that is used within the caller's write transaction. */
    public static final class LockedHolding {
        private final Holding holding;

        private LockedHolding(Holding holding) {
            this.holding = holding;
        }

        public Snapshot snapshot() {
            return holding.snapshot();
        }

        public void updateLegacyTerms(int lots, BigDecimal buyPrice) {
            holding.update(lots, buyPrice);
        }

        public void startV2Management() {
            holding.startV2Management();
        }
    }
}
