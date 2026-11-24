package tw.holdings.quote.marketstatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import tw.holdings.quote.Market;
import tw.holdings.quote.client.OfficialMarketClient;

/** 跨套件測試透過真實的名單核對流程確認市場，不公開正式內部類別。 */
public final class MarketTransferFixtures {
    private MarketTransferFixtures() {}
    public static void confirm(JdbcTemplate jdbc,MarketStatusService status,String code,Market market,LocalDate day) {
        var repository=new MarketStatusRepository(jdbc);
        repository.saveIdentity(code,new MarketStatusRepository.Identity("12345678",day.minusYears(2)));
        new MarketListingReconciler(repository,status).reconcile(Map.of(
            market,List.of(new OfficialMarketClient.Listing(code,market,day,"12345678","假資料公司")),
            market==Market.TWSE?Market.TPEX:Market.TWSE,List.of()),day);
    }
}
