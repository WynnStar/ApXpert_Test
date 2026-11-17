package tw.holdings.holding;

record ApiErrorResponse(ErrorDetails error) {
    ApiErrorResponse(String code, String message) {
        this(new ErrorDetails(code, message));
    }

    record ErrorDetails(String code, String message) {
    }
}
