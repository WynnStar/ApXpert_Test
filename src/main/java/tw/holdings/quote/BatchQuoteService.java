package tw.holdings.quote;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tw.holdings.quote.client.OfficialBatchQuoteClient;
import tw.holdings.quote.client.OfficialBatchQuoteClient.BatchUnavailable;
import tw.holdings.quote.client.OfficialSourceUnavailableException;

/** 每市場共用一個工作；排程、頁面及驗證皆經同一持久化進度。 */
@Service
class BatchQuoteService {
    private static final Logger LOGGER=LoggerFactory.getLogger(BatchQuoteService.class);
    private final OfficialBatchQuoteClient official;
    private final QuoteCacheRepository cache;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final QuoteMarketRefreshRepository states;
    private final MarketRefreshPolicy policy;
    private final Map<Market,Object> locks=new EnumMap<>(Market.class);

    BatchQuoteService(OfficialBatchQuoteClient official,QuoteCacheRepository cache,Clock clock,
                      TransactionTemplate transactions,QuoteMarketRefreshRepository states,MarketRefreshPolicy policy) {
        this.official=official;this.cache=cache;this.clock=clock;this.transactions=transactions;
        this.states=states;this.policy=policy;
        for (Market market:Market.values()) locks.put(market,new Object());
    }

    Optional<QuoteResult> lookup(String code,Market knownMarket) {
        if (knownMarket!=null) return lookupMarket(code,knownMarket);
        for (Market market:Market.values()) {
            var result=lookupMarket(code,market);
            if (result.isPresent()) return result;
        }
        return Optional.empty();
    }

    private Optional<QuoteResult> lookupMarket(String code,Market market) {
        ensure(market,taipeiNow().toLocalDate());
        return cache.find(taipeiNow().toLocalDate(),code).map(QuoteCacheRepository.Entry::result)
                .filter(r->r.status()==QuoteResult.Status.FOUND && r.market()==market);
    }

    Optional<Instant> nextAttempt(Market market,LocalDate target) {
        return states.find(market,target).map(QuoteMarketRefreshRepository.State::nextAttempt);
    }

    boolean individualAllowed(Market market,LocalDate day) {
        LocalTime time=taipeiNow().toLocalTime();
        if (!QuoteRefreshPolicy.isWeekday(taipeiNow()) || time.isBefore(LocalTime.of(13,30))
                || !time.isBefore(LocalTime.of(14,30))) return true;
        if (market!=null) return published(market,day);
        return java.util.Arrays.stream(Market.values()).allMatch(m->published(m,day));
    }

    boolean published(Market market,LocalDate target) {
        return states.find(market,target).map(s->s.completed()!=null).orElse(false)
                && cache.hasMarketPrice(market,target);
    }

    boolean ensure(Market market,LocalDate target) {
        synchronized (locks.get(market)) {
            var now=taipeiNow();
            var state=states.obtain(market,target,policy.firstAttempt(target));
            if (published(market,target)) return true;
            if (state.nextAttempt()!=null && now.toInstant().isBefore(state.nextAttempt())) return false;
            if (target.equals(now.toLocalDate()) && !now.toLocalTime().isBefore(LocalTime.of(13,30))
                    && now.toInstant().isBefore(state.windowStart())) return false;
            Instant normal=state.normalAttempt();
            if (normal==null && policy.normalWindow(now.toInstant(),state)) normal=now.toInstant();
            var attempted=new QuoteMarketRefreshRepository.State(market,target,state.windowStart(),now.toInstant(),
                    now.toInstant().plusSeconds(120),state.firstResponse(),state.recoveryStarted(),null,normal);
            states.save(attempted);
            try {
                var snapshot=official.fetch(market,target);
                var completed=taipeiNow();
                boolean valid=target.equals(snapshot.priceDate()) && snapshot.quotes().values().stream()
                        .anyMatch(q->target.equals(q.priceDate()) && q.close()!=null && q.close().signum()>0);
                transactions.executeWithoutResult(status->{
                    cache.saveBatch(completed,snapshot.quotes());
                    remember(attempted,completed,true,!valid,false,null);
                });
                return valid;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                remember(attempted,taipeiNow(),false,true,true,null);
            } catch (IOException error) {
                boolean recognized=error instanceof BatchUnavailable response && response.recognizable();
                boolean transport=!(error instanceof BatchUnavailable);
                Instant available=error instanceof OfficialSourceUnavailableException unavailable ? unavailable.availableAt():null;
                LOGGER.warn("Official batch unavailable: market={}, target={}, reason={}",market,target,error.getClass().getSimpleName());
                remember(attempted,taipeiNow(),recognized,true,transport,available);
            }
            return false;
        }
    }

    private void remember(QuoteMarketRefreshRepository.State s,ZonedDateTime now,boolean recognized,
                          boolean pending,boolean transport,Instant available) {
        Instant recovery=s.recoveryStarted();
        if (recognized && s.firstResponse()==null && s.normalAttempt()!=null
                && !now.toInstant().isBefore(policy.normalEnd(s.target())) && recovery==null) {
            recovery=now.toInstant();
        }
        Instant response=s.firstResponse()==null && recognized ? now.toInstant():s.firstResponse();
        var result=new QuoteMarketRefreshRepository.State(s.market(),s.target(),s.windowStart(),s.lastAttempt(),null,
                response,recovery,pending?null:now.toInstant(),s.normalAttempt());
        Instant next=pending?policy.next(now,result,transport,available):null;
        states.save(new QuoteMarketRefreshRepository.State(s.market(),s.target(),s.windowStart(),s.lastAttempt(),next,
                response,recovery,result.completed(),s.normalAttempt()));
    }

    private ZonedDateTime taipeiNow() { return clock.instant().atZone(QuoteRefreshPolicy.TAIPEI); }
}
