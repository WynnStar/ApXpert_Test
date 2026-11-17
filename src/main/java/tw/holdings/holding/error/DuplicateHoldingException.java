package tw.holdings.holding.error;

public final class DuplicateHoldingException extends RuntimeException {
    public DuplicateHoldingException(String code, Throwable cause) {
        this(code, cause, false);
    }

    public DuplicateHoldingException(String code, Throwable cause, boolean managedByV2) {
        super(code + " 已在持股中" + (managedByV2 ? "，請使用 2.0 網頁，或將 App 更新至 2.0 後操作" : ""),
                cause);
    }
}
