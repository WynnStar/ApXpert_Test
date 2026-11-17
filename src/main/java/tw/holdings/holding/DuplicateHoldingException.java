package tw.holdings.holding;

class DuplicateHoldingException extends RuntimeException {
    DuplicateHoldingException(String code, Throwable cause) {
        super(code + " 已在持股中", cause);
    }
}
