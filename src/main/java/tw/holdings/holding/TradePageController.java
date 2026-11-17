package tw.holdings.holding;

import java.time.LocalDate;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tw.holdings.trade.InsufficientSharesException;

@Controller
@RequestMapping("/v2/holdings")
class TradePageController {
    private final TradeHoldingService service;

    TradePageController(TradeHoldingService service) {
        this.service = service;
    }

    @GetMapping
    String list(Model model) {
        model.addAttribute("trade", emptyForm());
        return showList(model);
    }

    @GetMapping("/{id}")
    String detail(@PathVariable long id, Model model) {
        model.addAttribute("trade", emptyForm());
        return showDetail(id, model);
    }

    @PostMapping
    String create(@ModelAttribute("trade") @Valid TradeInput form, BindingResult errors,
                  Model model, RedirectAttributes redirect) {
        if (errors.hasErrors()) {
            return showList(model);
        }
        try {
            long id = service.create(form.stockCode(), form.firstBuy());
            redirect.addFlashAttribute("notice", "已記錄買進");
            return "redirect:/v2/holdings/" + id;
        } catch (HoldingApiController.ApiValidationException | DuplicateHoldingException | StockLookupException error) {
            errors.reject("trade", error.getMessage());
            return showList(model);
        }
    }

    @PostMapping("/{id}/trades")
    String append(@PathVariable long id, @ModelAttribute("trade") @Valid TradeInput form,
                  BindingResult errors, Model model, RedirectAttributes redirect) {
        if (errors.hasErrors()) {
            return showDetail(id, model);
        }
        try {
            service.append(id, form.trade(), form.confirmed());
            redirect.addFlashAttribute("notice", "已記錄交易");
            return "redirect:/v2/holdings/" + id;
        } catch (HoldingApiController.ApiValidationException | HoldingVersionConflict | InsufficientSharesException error) {
            errors.reject("trade", error.getMessage());
            return showDetail(id, model);
        }
    }

    @ExceptionHandler(HoldingNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    String notFound(HoldingNotFoundException exception, Model model) {
        model.addAttribute("message", exception.getMessage());
        return "holdings/not-found";
    }

    private TradeInput emptyForm() {
        LocalDate today = service.today();
        return new TradeInput(null, null, today, null, null, false);
    }

    private String showList(Model model) {
        var holdings = service.list();
        model.addAttribute("holdings", holdings);
        model.addAttribute("maxTradeDate", service.today());
        model.addAttribute("summary", TradePortfolioSummary.from(holdings));
        return "trades/list";
    }

    private String showDetail(long id, Model model) {
        model.addAttribute("history", service.history(id));
        model.addAttribute("maxTradeDate", service.today());
        return "trades/detail";
    }
}
