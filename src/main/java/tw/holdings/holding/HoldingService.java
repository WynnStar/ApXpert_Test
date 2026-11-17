package tw.holdings.holding;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class HoldingService {
    private final HoldingRepository repository;

    HoldingService(HoldingRepository repository) {
        this.repository = repository;
    }

    List<HoldingDetails> list() {
        return repository.findAllByOrderByIdAsc().stream().map(Holding::toDetails).toList();
    }

    HoldingDetails get(long id) {
        return find(id).toDetails();
    }

    @Transactional
    void create(String code, int lots, BigDecimal buyPrice) {
        String normalizedCode = code.strip();
        try {
            repository.saveAndFlush(new Holding(normalizedCode, lots, buyPrice));
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
