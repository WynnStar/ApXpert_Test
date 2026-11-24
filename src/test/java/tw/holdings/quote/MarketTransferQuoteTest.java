package tw.holdings.quote;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tw.holdings.quote.marketstatus.MarketStatusService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用假代號與隔離 H2，直接驗證跨市場快取及實際市場事件的讀取限制。 */
@JdbcTest(properties="spring.datasource.url=jdbc:h2:mem:market-transfer-quotes")
@Import(MarketTransferQuoteTest.MarketBeans.class)
class MarketTransferQuoteTest {
    private static final java.util.concurrent.atomic.AtomicInteger NEXT_CODE=new java.util.concurrent.atomic.AtomicInteger(9000);
    private String code;
    private static final LocalDate DAY=LocalDate.of(2026,11,24);
    @Autowired JdbcTemplate jdbc;
    @Autowired MarketStatusService markets;
    @Autowired Clock clock;
    QuoteCacheRepository cache;

    @TestConfiguration(proxyBeanMethods=false)
    @ComponentScan(basePackageClasses=MarketStatusService.class,useDefaultFilters=false,
        includeFilters=@ComponentScan.Filter(type=FilterType.REGEX,
            pattern="tw\\.holdings\\.quote\\.marketstatus\\.MarketStatus(Repository|Service)"))
    static class MarketBeans {
        @Bean Clock clock() {
            return Clock.fixed(DAY.atTime(16,0).atZone(QuoteRefreshPolicy.TAIPEI).toInstant(),ZoneOffset.UTC);
        }
    }

    @BeforeEach void setup() { code=String.valueOf(NEXT_CODE.incrementAndGet());cache=new QuoteCacheRepository(jdbc); }
    Market previous(Market current) { return current==Market.TWSE?Market.TPEX:Market.TWSE; }
    QuoteResult quote(Market market,LocalDate date,String price) {
        return QuoteResult.found("假資料公司",market,new BigDecimal(price),date);
    }
    void seedOld(Market current) {
        var old=quote(previous(current),DAY.minusDays(1),"100.00");
        cache.save(DAY.minusDays(1),code,new QuoteCacheRepository.Entry(old,0,0,clock.instant()),clock.instant());
    }
    QuoteResult seedNew(Market current) {
        var fresh=quote(current,DAY,"120.00");
        cache.saveBatch(clock.instant().atZone(QuoteRefreshPolicy.TAIPEI),Map.of(code,fresh));
        return fresh;
    }
    void event(Market market,LocalDate effective) {
        jdbc.update("INSERT INTO market_event(code,market,effective_date,kind,stock_name) VALUES(?,?,?,'RELISTED',?)",
            code,market.name(),effective,"假資料公司");
    }
    QuoteService facade() {
        // 本次只驗證已存在快取的讀取，背景工作不執行，也不呼叫官方。
        return new QuoteService(cache,mock(OfficialQuoteLookup.class),clock,
            mock(ExecutorService.class),markets,mock(BatchQuoteService.class));
    }

    @ParameterizedTest @EnumSource(Market.class)
    void newerBatchCanChangeMarketAndOlderBatchCannotRollItBack(Market current) {
        seedOld(current);var fresh=seedNew(current);
        assertEquals(fresh,cache.find(DAY,code).orElseThrow().result());
        cache.saveBatch(clock.instant().atZone(QuoteRefreshPolicy.TAIPEI),
            Map.of(code,quote(previous(current),DAY.minusDays(1),"100.00")));
        assertEquals(fresh,cache.find(DAY,code).orElseThrow().result());
    }

    @ParameterizedTest @EnumSource(Market.class)
    void updatedMarketEventMakesNewQuoteVisibleDespiteOldHoldingHint(Market current) {
        seedOld(current);var fresh=seedNew(current);event(current,DAY);
        assertEquals(fresh,facade().forDisplay(code,previous(current)));
    }

    @ParameterizedTest @EnumSource(Market.class)
    void staleEventHidesUnconfirmedPriceUntilCorrectMarketIsConfirmed(Market current) {
        seedOld(current);var fresh=seedNew(current);event(previous(current),DAY.minusYears(2));
        var quotes=facade();
        assertNull(quotes.forDisplay(code,previous(current)).close(),"未確認轉板前，不沿用舊市場價格");
        tw.holdings.quote.marketstatus.MarketTransferFixtures.confirm(jdbc,markets,code,current,DAY);
        assertEquals(fresh,quotes.forDisplay(code,previous(current)),"確認轉板後立刻讀取已保存的新行情");
    }

    @ParameterizedTest @EnumSource(Market.class)
    void sameDateMarketConflictNeedsAnEffectiveConfirmation(Market current) {
        var old=quote(previous(current),DAY,"100.00");
        cache.save(DAY,code,new QuoteCacheRepository.Entry(old,0,0,clock.instant()),clock.instant());
        var fresh=seedNew(current);
        assertEquals(old,cache.find(DAY,code).orElseThrow().result(),"未確認時不能任意切換同日行情來源");
        event(current,DAY);seedNew(current);
        assertEquals(fresh,cache.find(DAY,code).orElseThrow().result());
        cache.saveBatch(clock.instant().atZone(QuoteRefreshPolicy.TAIPEI),java.util.Map.of(code,old));
        assertEquals(fresh,cache.find(DAY,code).orElseThrow().result(),"舊市場同日資料不能蓋掉已確認新市場");
    }

    @ParameterizedTest @EnumSource(Market.class)
    void confirmedMarketRejectsEvenNewerWrongMarketQuotesAndMissingPrices(Market current) {
        event(current,DAY.minusDays(3));
        var correct=quote(current,DAY.minusDays(1),"110.00");
        cache.saveBatch(clock.instant().atZone(QuoteRefreshPolicy.TAIPEI),Map.of(code,correct));
        cache.saveBatch(clock.instant().atZone(QuoteRefreshPolicy.TAIPEI),Map.of(code,quote(previous(current),DAY,"120.00")));
        assertEquals(correct,cache.find(DAY,code).orElseThrow().result());
        cache.saveBatch(clock.instant().atZone(QuoteRefreshPolicy.TAIPEI),Map.of(code,QuoteResult.found("假資料公司",previous(current),null,null)));
        assertEquals(correct,cache.find(DAY,code).orElseThrow().result());
    }

    @ParameterizedTest @EnumSource(Market.class)
    void confirmedMarketAlsoGuardsFirstInsertForTheCurrentLookupDay(Market current) {
        event(current,DAY);
        cache.saveBatch(clock.instant().atZone(QuoteRefreshPolicy.TAIPEI),Map.of(code,quote(previous(current),DAY,"120.00")));
        assertTrue(cache.find(DAY,code).isEmpty());
        var correct=seedNew(current);
        assertEquals(correct,cache.find(DAY,code).orElseThrow().result());
    }

    @ParameterizedTest @EnumSource(Market.class)
    void displayCanFillMissingNewMarketPriceAfterConfirmation(Market current) {
        var old=quote(previous(current),DAY,"100.00");
        cache.save(DAY,code,new QuoteCacheRepository.Entry(old,0,0,clock.instant()),clock.instant());
        event(previous(current),DAY.minusYears(2));
        tw.holdings.quote.marketstatus.MarketTransferFixtures.confirm(jdbc,markets,code,current,DAY);
        var batches=mock(BatchQuoteService.class);
        when(batches.lookup(code,current)).thenReturn(java.util.Optional.empty());
        when(batches.individualAllowed(current,DAY)).thenReturn(true);
        var official=mock(OfficialQuoteLookup.class);
        var fresh=quote(current,DAY,"120.00");
        when(official.lookup(code,current,DAY,false)).thenReturn(fresh);
        var workers=mock(ExecutorService.class);
        doAnswer(call->{call.<Runnable>getArgument(0).run();return null;}).when(workers).execute(org.mockito.ArgumentMatchers.any(Runnable.class));
        var quotes=new QuoteService(cache,official,clock,workers,markets,batches);
        quotes.forDisplay(code,previous(current));
        assertEquals(fresh,quotes.forDisplay(code,previous(current)));
        verify(official,times(1)).lookup(code,current,DAY,false);
    }
}
