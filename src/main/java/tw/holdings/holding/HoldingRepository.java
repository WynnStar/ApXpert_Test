package tw.holdings.holding;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

interface HoldingRepository extends JpaRepository<Holding, Long> {
    List<Holding> findAllByOrderByIdAsc();
}
