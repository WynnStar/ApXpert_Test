package tw.holdings.trade;

import java.time.LocalDate;

public class InsufficientSharesException extends RuntimeException {
    InsufficientSharesException(LocalDate date, long requested, long available) {
        super(date + " 賣出 " + requested + " 股，當時僅持有 " + available + " 股");
    }

    private InsufficientSharesException(String message, Throwable cause) {
        super(message, cause);
    }

    public InsufficientSharesException withHistoricalBuyHint() {
        return new InsufficientSharesException(getMessage()
                + "。若當時已有持股，請先補登過往的買進紀錄，再重新登記這筆賣出。", this);
    }
}
