package tw.holdings.holding;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import tw.holdings.quote.Market;

interface HoldingRepository extends JpaRepository<Holding, Long> {
    List<Holding> findAllByOrderByIdAsc();

    List<Holding> findAllByV2ManagedFalseOrderByIdAsc();

    boolean existsByCode(String code);

    boolean existsByCodeAndV2ManagedTrue(String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT h FROM Holding h WHERE h.id = :id")
    Optional<Holding> lockById(@Param("id") long id);

    @Modifying
    @Transactional
    @Query("""
            UPDATE Holding h SET h.name = :name, h.market = :market
            WHERE h.id = :id AND (h.name IS NULL OR h.market IS NULL)
            """)
    int fillMissingIdentity(@Param("id") long id, @Param("name") String name,
                            @Param("market") Market market);
}
