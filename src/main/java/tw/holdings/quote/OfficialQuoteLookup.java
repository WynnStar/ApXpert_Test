package tw.holdings.quote;

import java.io.IOException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tw.holdings.quote.client.QuoteSource;

@Component
class OfficialQuoteLookup {
    private static final Logger LOGGER = LoggerFactory.getLogger(OfficialQuoteLookup.class);
    private final List<QuoteSource> sources;

    OfficialQuoteLookup(List<QuoteSource> sources) {
        this.sources = sources.stream().sorted(Comparator.comparing(QuoteSource::market)).toList();
    }

    QuoteResult lookup(String code, Market knownMarket, LocalDate day, boolean onlyCurrentMonth) {
        Set<Market> unavailable = EnumSet.noneOf(Market.class);
        QuoteResult identity = null;
        YearMonth current = YearMonth.from(day);
        for (YearMonth month : (onlyCurrentMonth ? List.of(current) : List.of(current, current.minusMonths(1)))) {
            for (QuoteSource source : sources) {
                Market preferred = identity == null ? knownMarket : identity.market();
                if (unavailable.contains(source.market()) || (preferred != null && preferred != source.market())) {
                    continue;
                }
                QuoteResult result = fetch(source, code, month, day);
                switch (result.status()) {
                    case FOUND -> {
                        if (result.close() != null) {
                            return result;
                        }
                        identity = result;
                    }
                    case UNAVAILABLE -> unavailable.add(source.market());
                    case NOT_FOUND -> {
                        // 查無當月資料時可能是月初休市，仍須檢查上個月。
                    }
                }
            }
        }
        if (identity != null) {
            return identity;
        }
        return unavailable.isEmpty() ? QuoteResult.notFound() : QuoteResult.unavailable();
    }

    private QuoteResult fetch(QuoteSource source, String code, YearMonth month, LocalDate day) {
        try {
            return source.fetch(code, month, day);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return QuoteResult.unavailable();
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Official quote unavailable: market={}, code={}, month={}, reason={}",
                    source.market(), code, month, exception.getClass().getSimpleName());
            return QuoteResult.unavailable();
        }
    }
}
