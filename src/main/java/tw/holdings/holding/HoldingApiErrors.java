package tw.holdings.holding;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tw.holdings.quote.QuoteResult;
import tw.holdings.trade.InsufficientSharesException;

@RestControllerAdvice(assignableTypes = {HoldingApiController.class, TradeApiController.class})
class HoldingApiErrors {

    @ExceptionHandler(HoldingValidationException.class)
    ResponseEntity<ApiErrorResponse> validation(HoldingValidationException exception) {
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", exception.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiErrorResponse> malformedJson() {
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "請使用 JSON，且欄位必須符合指定型別");
    }

    @ExceptionHandler(HoldingNotFoundException.class)
    ResponseEntity<ApiErrorResponse> notFound(HoldingNotFoundException exception) {
        return respond(HttpStatus.NOT_FOUND, "NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiErrorResponse> invalidId() {
        return respond(HttpStatus.NOT_FOUND, "NOT_FOUND", "找不到指定持股");
    }

    @ExceptionHandler(DuplicateHoldingException.class)
    ResponseEntity<ApiErrorResponse> duplicate(DuplicateHoldingException exception) {
        return respond(HttpStatus.CONFLICT, "DUPLICATE_CODE", exception.getMessage());
    }

    @ExceptionHandler(StockLookupException.class)
    ResponseEntity<ApiErrorResponse> stockLookup(StockLookupException exception) {
        if (exception.status() == QuoteResult.Status.NOT_FOUND) {
            return respond(HttpStatus.NOT_FOUND, "STOCK_NOT_FOUND", exception.getMessage());
        }
        return respond(HttpStatus.SERVICE_UNAVAILABLE, "QUOTE_UNAVAILABLE", exception.getMessage());
    }

    @ExceptionHandler(StockIdentityUnavailableException.class)
    ResponseEntity<ApiErrorResponse> identityUnavailable(StockIdentityUnavailableException exception) {
        return respond(HttpStatus.SERVICE_UNAVAILABLE, "QUOTE_UNAVAILABLE", exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> invalidFields(MethodArgumentNotValidException exception) {
        var error = exception.getBindingResult().getFieldError();
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                error == null ? "欄位格式錯誤" : error.getDefaultMessage());
    }

    @ExceptionHandler(HoldingVersionConflict.class)
    ResponseEntity<ApiErrorResponse> versionConflict(HoldingVersionConflict exception) {
        return respond(HttpStatus.CONFLICT, exception.code(), exception.getMessage());
    }

    @ExceptionHandler(InsufficientSharesException.class)
    ResponseEntity<ApiErrorResponse> insufficientShares(InsufficientSharesException exception) {
        return respond(HttpStatus.CONFLICT, "INSUFFICIENT_SHARES", exception.getMessage());
    }

    private ResponseEntity<ApiErrorResponse> respond(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(code, message));
    }
}
