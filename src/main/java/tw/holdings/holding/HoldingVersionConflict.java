package tw.holdings.holding;

class HoldingVersionConflict extends RuntimeException {
    private final String code;

    private HoldingVersionConflict(String code, String message) {
        super(message);
        this.code = code;
    }

    static HoldingVersionConflict managedByV2() {
        return new HoldingVersionConflict("HOLDING_REQUIRES_V2",
                "這筆持股已由 2.0 管理，請使用 2.0 網頁操作");
    }

    static HoldingVersionConflict confirmationRequired() {
        return new HoldingVersionConflict("CONFIRM_V2_REQUIRED",
                "請確認這筆交易會將持股移出 App 1.0 的清單與總計");
    }

    String code() {
        return code;
    }
}
