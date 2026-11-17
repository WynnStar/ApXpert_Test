package tw.holdings.holding.v2;

import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import tw.holdings.holding.error.HoldingNotFoundException;
import tw.holdings.holding.error.HoldingTradeNotFoundException;

@ControllerAdvice(assignableTypes = {TradePageController.class, TradeMaintenanceController.class})
class TradePageErrors {
    @ExceptionHandler({HoldingNotFoundException.class, HoldingTradeNotFoundException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    String notFound(RuntimeException exception, Model model) {
        model.addAttribute("message", exception.getMessage());
        return "holdings/not-found";
    }
}
