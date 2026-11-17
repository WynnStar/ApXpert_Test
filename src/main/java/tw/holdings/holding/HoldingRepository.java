package tw.holdings.holding;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import tw.holdings.quote.Market;

interface HoldingRepository extends JpaRepository<Holding, Long> {
    List<Holding> findAllByOrderByIdAsc();

    boolean existsByCode(String code);

    @Modifying
    @Transactional
    @Query("""
            UPDATE Holding h SET h.name = :name, h.market = :market
            WHERE h.id = :id AND (h.name IS NULL OR h.market IS NULL)
            """)
    int fillMissingIdentity(@Param("id") long id, @Param("name") String name,
                            @Param("market") Market market);
}
