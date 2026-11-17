package tw.holdings.holding;

import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

@ControllerAdvice(assignableTypes = {TradePageController.class, TradeMaintenanceController.class})
class TradePageErrors {
    @ExceptionHandler({HoldingNotFoundException.class, HoldingTradeNotFoundException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    String notFound(RuntimeException exception, Model model) {
        model.addAttribute("message", exception.getMessage());
        return "holdings/not-found";
    }
}
