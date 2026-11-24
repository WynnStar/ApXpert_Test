package tw.holdings.quote.marketstatus;

import java.time.LocalDate;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tw.holdings.quote.Market;
import tw.holdings.quote.client.OfficialMarketClient;

/** 比對兩市場完整名單後才修正身分；不以行情價格推斷轉板。 */
final class MarketListingReconciler {
    private static final Logger LOGGER=LoggerFactory.getLogger(MarketListingReconciler.class);
    private final MarketStatusRepository repository;
    private final MarketStatusService status;
    MarketListingReconciler(MarketStatusRepository repository,MarketStatusService status) {
        this.repository=repository;this.status=status;
    }
    void reconcile(Map<Market,List<OfficialMarketClient.Listing>> rosters,LocalDate day) {
        if(rosters.size()!=Market.values().length)return;
        Map<String,List<OfficialMarketClient.Listing>> byCode=new HashMap<>();
        rosters.forEach((market,rows)->rows.stream().filter(row->!row.date().isAfter(day)).forEach(row->{
            if(row.market()!=market)throw new IllegalArgumentException("Roster market mismatch");
            byCode.computeIfAbsent(row.code(),ignored->new ArrayList<>()).add(row);
        }));
        byCode.forEach((code,rows)->{
            if(rows.size()!=1) {
                LOGGER.warn("Conflicting official listing rosters: code={}",code);
                return;
            }
            reconcile(rows.get(0),day);
        });
    }
    private void reconcile(OfficialMarketClient.Listing listing,LocalDate day) {
        var previous=repository.identity(listing.code()).orElse(null);
        var latest=status.listingEvent(listing.code(),day).orElse(null);
        if(previous==null) {
            if(latest==null) {
                repository.saveIdentity(listing.code(),new MarketStatusRepository.Identity(listing.issuer(),listing.date()));
                status.confirmReview(listing.code(),listing.market());
            }
            return;
        }
        if(!previous.issuerId().equals(listing.issuer())) {
            LOGGER.warn("Listing issuer mismatch; manual review required: code={}",listing.code());
            return;
        }
        boolean laterListing=listing.date().isAfter(previous.listedOn());
        boolean afterEvent=latest==null || listing.date().isAfter(latest.date())
                || latest.kind()==MarketEvent.Kind.DELISTED && listing.date().equals(latest.date());
        boolean changed=latest==null || latest.kind()==MarketEvent.Kind.DELISTED || latest.market()!=listing.market();
        if(laterListing && afterEvent && changed) {
            // date 為官方該市場上市／上櫃日期，絕不用本次下載日期代替。
            repository.save(new MarketEvent(listing.code(),listing.market(),listing.date(),MarketEvent.Kind.RELISTED,listing.name()));
        }
        var confirmed=status.listingEvent(listing.code(),day).orElse(null);
        if(confirmed==null || confirmed.kind()==MarketEvent.Kind.RELISTED && confirmed.market()==listing.market())
            status.confirmReview(listing.code(),listing.market());
    }
}
