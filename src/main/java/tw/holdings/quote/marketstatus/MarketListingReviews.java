package tw.holdings.quote.marketstatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import tw.holdings.quote.Market;

/** 合併身分核對工作、保留未決狀態，避免每筆行情都重新下載兩市場名單。 */
final class MarketListingReviews {
    private record Review(Market market,LocalDate day) {}
    private final Clock clock;
    private final Set<String> queued=new HashSet<>();
    private final Set<String> unresolved=new HashSet<>();
    private final Map<String,Review> verified=new HashMap<>();
    private Instant next=Instant.MIN;
    MarketListingReviews(Clock clock) { this.clock=clock; }
    synchronized void request(String code) {
        verified.remove(code);
        if(unresolved.size()<1000 || unresolved.contains(code)) { queued.add(code);unresolved.add(code); }
    }
    synchronized boolean pending(String code) { return unresolved.contains(code); }
    synchronized boolean verified(String code,Market market) {
        var result=verified.get(code);
        return result!=null && result.market()==market && result.day().equals(LocalDate.now(clock));
    }
    synchronized void confirm(String code,Market market) {
        verified.put(code,new Review(market,LocalDate.now(clock)));
        queued.remove(code);unresolved.remove(code);
    }
    synchronized Set<String> claim() {
        var now=clock.instant();
        if(queued.isEmpty() || now.isBefore(next))return Set.of();
        var pending=Set.copyOf(queued);
        queued.clear();next=now.plus(Duration.ofMinutes(30));
        return pending;
    }
}
