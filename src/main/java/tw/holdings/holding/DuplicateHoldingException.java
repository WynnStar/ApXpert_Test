package tw.holdings.holding;

class DuplicateHoldingException extends RuntimeException {
    DuplicateHoldingException(String code, Throwable cause) {
        this(code, cause, false);
    }

    DuplicateHoldingException(String code, Throwable cause, boolean managedByV2) {
        super(code + " 已在持股中" + (managedByV2 ? "，請使用 2.0 網頁操作" : ""), cause);
    }
}
