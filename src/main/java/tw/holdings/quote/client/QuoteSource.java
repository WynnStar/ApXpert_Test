package tw.holdings.quote.client;

import java.io.IOException;
import java.time.LocalDate;
import java.time.YearMonth;

import tw.holdings.quote.Market;
import tw.holdings.quote.QuoteResult;

public interface QuoteSource {
    Market market();

    QuoteResult fetch(String code, YearMonth month, LocalDate today)
            throws IOException, InterruptedException;
}
