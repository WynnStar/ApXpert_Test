package tw.holdings.quote.marketstatus;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import tw.holdings.quote.Market;
import tw.holdings.quote.client.OfficialMarketClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 模擬每日同步，驗證有效公司名單能否修正先前的市場事件。 */
@JdbcTest(properties="spring.datasource.url=jdbc:h2:mem:market-transfer-reconciliation")
class MarketTransferReconciliationTest {
    @Autowired JdbcTemplate jdbc;
    @ParameterizedTest @EnumSource(Market.class)
    void currentOfficialRosterShouldUpdateAStaleMarketEvent(Market current) throws Exception {
        var day=LocalDate.of(2026,11,24);
        var previous=current==Market.TWSE?Market.TPEX:Market.TWSE;
        var repository=new MarketStatusRepository(jdbc);
        var clock=Clock.fixed(day.atStartOfDay(ZoneOffset.UTC).toInstant(),ZoneOffset.UTC);
        var status=new MarketStatusService(repository,clock);
        var official=mock(OfficialMarketClient.class);
        var history=mock(DelistedQuoteHistory.class);
        String code="9999";
        repository.saveIdentity(code,new MarketStatusRepository.Identity("12345678",day.minusYears(2)));
        repository.save(new MarketEvent(code,previous,day.minusYears(2),MarketEvent.Kind.RELISTED,"假資料公司"));
        when(official.delistings(any(),anyInt())).thenReturn(List.of());
        when(official.transfersToTwse(anyInt())).thenReturn(List.of());
        when(official.halts(any())).thenReturn(List.of());
        when(official.stops(any())).thenReturn(List.of());
        when(official.listings(any())).thenReturn(List.of());
        when(official.listings(current)).thenReturn(List.of(
            new OfficialMarketClient.Listing(code,current,day,"12345678","假資料公司")));
        // 舊年度已同步，避免本測試混入歷史回補的無關流程。
        for(int year=1994;year<day.getYear()-1;year++) {
            repository.markTpexYearImported(year);repository.markTpexTransfersImported(year);
        }
        new MarketStatusSyncService(repository,status,official,history).sync(day);
        var result=status.quoteListing(code,day).orElseThrow();
        assertEquals(current,result.market(),"有效公司名單已轉板，但市場事件仍指向舊市場");
        assertEquals(day,result.effectiveDate());
    }

    @org.junit.jupiter.api.Test
    void rejectsReusedCodeContradictoryRostersAndFutureOrOldEffectiveDates() {
        var day=LocalDate.of(2026,11,24);
        var repository=new MarketStatusRepository(jdbc);
        var status=new MarketStatusService(repository,Clock.fixed(day.atStartOfDay(ZoneOffset.UTC).toInstant(),ZoneOffset.UTC));
        var reconciler=new MarketListingReconciler(repository,status);
        for(String code:List.of("9991","9992","9993","9994")) {
            repository.saveIdentity(code,new MarketStatusRepository.Identity("12345678",day.minusYears(2)));
            repository.save(new MarketEvent(code,Market.TPEX,day.minusYears(1),MarketEvent.Kind.RELISTED,"原公司"));
        }
        var newRows=List.of(
            new OfficialMarketClient.Listing("9991",Market.TWSE,day,"87654321","代號重用"),
            new OfficialMarketClient.Listing("9992",Market.TWSE,day,"12345678","雙方矛盾"),
            new OfficialMarketClient.Listing("9993",Market.TWSE,day.plusDays(1),"12345678","尚未生效"),
            new OfficialMarketClient.Listing("9994",Market.TWSE,day.minusYears(2),"12345678","舊名單"));
        reconciler.reconcile(java.util.Map.of(Market.TWSE,newRows,Market.TPEX,List.of(
            new OfficialMarketClient.Listing("9992",Market.TPEX,day.minusYears(1),"12345678","雙方矛盾"))),day);
        for(String code:List.of("9991","9992","9993","9994"))assertEquals(Market.TPEX,status.quoteListing(code,day).orElseThrow().market());
    }

    @org.junit.jupiter.api.Test
    void marketReviewRequestsAreMergedAndThrottledWithoutLosingPendingCodes() {
        var day=LocalDate.of(2026,11,24);
        var status=new MarketStatusService(new MarketStatusRepository(jdbc),Clock.fixed(day.atStartOfDay(ZoneOffset.UTC).toInstant(),ZoneOffset.UTC));
        status.requestReview("9999");status.requestReview("9999");
        assertEquals(java.util.Set.of("9999"),status.claimListingReviews());
        status.requestReview("9998");assertTrue(status.claimListingReviews().isEmpty());
        status.confirmReview("9998",Market.TWSE);assertTrue(status.marketReviewed("9998",Market.TWSE));
    }
}
