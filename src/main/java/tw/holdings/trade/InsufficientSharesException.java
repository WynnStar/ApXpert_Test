package tw.holdings.trade;

import java.time.LocalDate;

public class InsufficientSharesException extends RuntimeException {
    InsufficientSharesException(LocalDate date, long requested, long available) {
        super(date + " 賣出 " + requested + " 股，當時僅持有 " + available + " 股");
    }
}
