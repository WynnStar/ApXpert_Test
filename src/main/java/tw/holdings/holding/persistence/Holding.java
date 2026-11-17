package tw.holdings.holding.persistence;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import tw.holdings.quote.Market;

@Entity
@Table(name = "holdings")
class Holding {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false, length = 6)
    private String code;

    private Integer lots;

    @Column(name = "buy_price", precision = 38, scale = 2)
    private BigDecimal buyPrice;

    @Column(name = "stock_name", length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(length = 4)
    private Market market;

    @Column(name = "v2_managed", nullable = false)
    private boolean v2Managed;

    protected Holding() {
    }

    Holding(String code, int lots, BigDecimal buyPrice, String name, Market market) {
        this.code = code;
        this.name = name;
        this.market = market;
        update(lots, buyPrice);
    }

    static Holding managed(String code, String name, Market market) {
        Holding holding = new Holding();
        holding.code = code;
        holding.name = name;
        holding.market = market;
        holding.v2Managed = true;
        return holding;
    }

    long id() { return id; }

    void startV2Management() {
        v2Managed = true;
        lots = null;
        buyPrice = null;
    }

    void update(int lots, BigDecimal buyPrice) {
        this.lots = lots;
        this.buyPrice = buyPrice;
    }

    HoldingStore.Snapshot snapshot() {
        return new HoldingStore.Snapshot(id, code, lots, buyPrice, name, market, v2Managed);
    }
}
