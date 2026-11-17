package tw.holdings.quote;

import java.io.IOException;
import java.time.LocalDate;
import java.time.YearMonth;

interface QuoteSource {
    Market market();

    QuoteResult fetch(String code, YearMonth month, LocalDate today)
            throws IOException, InterruptedException;
}
