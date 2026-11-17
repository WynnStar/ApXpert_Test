package tw.holdings.holding;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "holdings")
class Holding {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false, length = 6)
    private String code;

    @Column(nullable = false)
    private int lots;

    @Column(name = "buy_price", nullable = false, precision = 38, scale = 2)
    private BigDecimal buyPrice;

    protected Holding() {
    }

    Holding(String code, int lots, BigDecimal buyPrice) {
        this.code = code;
        update(lots, buyPrice);
    }

    void update(int lots, BigDecimal buyPrice) {
        this.lots = lots;
        this.buyPrice = buyPrice;
    }

    HoldingDetails toDetails() {
        return new HoldingDetails(id, code, lots, buyPrice);
    }
}
