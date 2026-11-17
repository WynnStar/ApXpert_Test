package tw.holdings.holding.v2;

import java.time.LocalDate;

import jakarta.validation.Valid;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tw.holdings.holding.error.DuplicateHoldingException;
import tw.holdings.holding.error.HoldingValidationException;
import tw.holdings.holding.error.HoldingVersionConflict;
import tw.holdings.holding.error.StockLookupException;
import tw.holdings.trade.InsufficientSharesException;

@Controller
@RequestMapping("/v2/holdings")
class TradePageController {
    private final TradeHoldingService commands;
    private final TradeHoldingQueryService queries;
    private final TradeDateRules dates;

    TradePageController(TradeHoldingService commands, TradeHoldingQueryService queries, TradeDateRules dates) {
        this.commands = commands;
        this.queries = queries;
        this.dates = dates;
    }

    @GetMapping
    String list(Model model) {
        model.addAttribute("trade", emptyForm());
        return showList(model);
    }

    @GetMapping("/trade-date-range")
    @ResponseBody
    TradeDateRules.Range tradeDateRange(@RequestParam String code) {
        return dates.range(code);
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
            long id = commands.create(form.stockCode(), form.firstBuy());
            redirect.addFlashAttribute("notice", "已記錄買進");
            return "redirect:/v2/holdings/" + id;
        } catch (HoldingValidationException | DuplicateHoldingException | StockLookupException error) {
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
            commands.append(id, form.trade(), form.confirmed());
            redirect.addFlashAttribute("notice", "已記錄交易");
            return "redirect:/v2/holdings/" + id;
        } catch (HoldingValidationException | HoldingVersionConflict | InsufficientSharesException error) {
            errors.reject("trade", error.getMessage());
            return showDetail(id, model);
        }
    }

    private TradeInput emptyForm() {
        LocalDate today = dates.today();
        return new TradeInput(null, null, today, null, null, false);
    }

    private String showList(Model model) {
        var holdings = queries.list();
        model.addAttribute("holdings", holdings);
        TradeInput form = (TradeInput) model.asMap().get("trade");
        addDateRange(dates.range(form.code()), model);
        model.addAttribute("summary", TradePortfolioSummary.from(holdings));
        return "trades/list";
    }

    private String showDetail(long id, Model model) {
        var history = queries.history(id);
        model.addAttribute("history", history);
        addDateRange(dates.range(history.holding().code()), model);
        return "trades/detail";
    }

    private void addDateRange(TradeDateRules.Range range, Model model) {
        model.addAttribute("minTradeDate", range.minDate());
        model.addAttribute("maxTradeDate", range.maxDate());
        model.addAttribute("firstListingDate", range.firstListingDate());
    }
}
