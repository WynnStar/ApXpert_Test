package tw.holdings.quote.client;

import java.io.IOException;

/** 已辨識的官方回應，指定日期行情尚未公布。 */
class UnpublishedBatchException extends IOException {
    private static final long serialVersionUID=1L;
    UnpublishedBatchException(String message) { super(message); }
}
