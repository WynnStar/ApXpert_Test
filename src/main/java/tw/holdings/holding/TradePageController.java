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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tw.holdings.trade.InsufficientSharesException;
import tw.holdings.trade.TradeLedgerService.Direction;
import tw.holdings.trade.TradeOrderException;

@Controller
@RequestMapping("/v2/holdings")
class TradePageController {
    private final TradeHoldingService service;
    private final TradeDateRules dates;

    TradePageController(TradeHoldingService service, TradeDateRules dates) {
        this.service = service;
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
        return dates.newTradeRange(code);
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

    @GetMapping("/{id}/delete")
    String confirmDelete(@PathVariable long id, Model model) {
        model.addAttribute("holding", service.deleteTarget(id));
        return "trades/delete";
    }

    @PostMapping("/{id}/delete")
    String delete(@PathVariable long id, RedirectAttributes redirect) {
        service.delete(id);
        redirect.addFlashAttribute("notice", "已刪除整檔持股及交易紀錄");
        return "redirect:/v2/holdings";
    }

    @GetMapping("/{id}/trades/{tradeId}/edit")
    String editTrade(@PathVariable long id, @PathVariable long tradeId, Model model) {
        var target = service.tradeTarget(id, tradeId);
        model.addAttribute("tradeEdit", TradeEditForm.from(target.entry().trade()));
        return showTradeEdit(target, model);
    }

    @PostMapping("/{id}/trades/{tradeId}/edit")
    String updateTrade(@PathVariable long id, @PathVariable long tradeId,
                       @ModelAttribute("tradeEdit") @Valid TradeEditForm form, BindingResult errors,
                       Model model, RedirectAttributes redirect) {
        if (!errors.hasErrors()) {
            try {
                service.editTrade(id, tradeId, form);
                redirect.addFlashAttribute("notice", "已修改交易紀錄");
                return "redirect:/v2/holdings/" + id;
            } catch (HoldingApiController.ApiValidationException error) {
                errors.reject("tradeEdit", error.getMessage());
            } catch (HoldingVersionConflict error) {
                errors.rejectValue("confirmV2", "confirmV2", "請先確認由 2.0 管理這檔持股");
            } catch (InsufficientSharesException error) {
                errors.reject("tradeEdit", "無法修改。修改後會發生超賣：" + error.getMessage());
            }
        }
        return showTradeEdit(service.tradeTarget(id, tradeId), model);
    }

    private String showTradeEdit(TradeHoldingService.TradeTarget target, Model model) {
        model.addAttribute("target", target);
        addDateRange(dates.editRange(target.holding().code()), model);
        return "trades/edit-trade";
    }

    @GetMapping("/{id}/trades/{tradeId}/delete")
    String confirmTradeDelete(@PathVariable long id, @PathVariable long tradeId, Model model) {
        model.addAttribute("target", service.tradeTarget(id, tradeId));
        return "trades/delete-trade";
    }

    @PostMapping("/{id}/trades/{tradeId}/delete")
    String deleteTrade(@PathVariable long id, @PathVariable long tradeId, @RequestParam boolean lastTrade,
                       RedirectAttributes redirect) {
        String detail = "redirect:/v2/holdings/" + id;
        try {
            return switch (service.deleteTrade(id, tradeId, lastTrade)) {
                case TRADE_DELETED -> {
                    redirect.addFlashAttribute("notice", "已刪除交易紀錄");
                    yield detail;
                }
                case HOLDING_DELETED -> {
                    redirect.addFlashAttribute("notice", "已刪除整檔持股及交易紀錄");
                    yield "redirect:/v2/holdings";
                }
                case CONFIRM_AGAIN -> {
                    redirect.addFlashAttribute("deleteError", "交易筆數已變更，請重新確認刪除範圍。");
                    yield detail + "/trades/" + tradeId + "/delete";
                }
            };
        } catch (InsufficientSharesException error) {
            redirect.addFlashAttribute("deleteError", "無法刪除。刪除後會發生超賣：" + error.getMessage());
            return detail;
        }
    }

    @PostMapping("/{id}/trades/{tradeId}/move")
    String move(@PathVariable long id, @PathVariable long tradeId, @RequestParam Direction direction,
                RedirectAttributes redirect) {
        try {
            service.move(id, tradeId, direction);
            redirect.addFlashAttribute("notice", "已調整同日交易順序");
        } catch (TradeOrderException error) {
            redirect.addFlashAttribute("orderError", error.getMessage());
        } catch (InsufficientSharesException error) {
            redirect.addFlashAttribute("orderError", "無法調整順序。調整後會發生超賣：" + error.getMessage());
        }
        return "redirect:/v2/holdings/" + id;
    }

    @ExceptionHandler({HoldingNotFoundException.class, HoldingTradeNotFoundException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    String notFound(RuntimeException exception, Model model) {
        model.addAttribute("message", exception.getMessage());
        return "holdings/not-found";
    }

    private TradeInput emptyForm() {
        LocalDate today = dates.today();
        return new TradeInput(null, null, today, null, null, false);
    }

    private String showList(Model model) {
        var holdings = service.list();
        model.addAttribute("holdings", holdings);
        TradeInput form = (TradeInput) model.asMap().get("trade");
        addDateRange(dates.newTradeRange(form.code()), model);
        model.addAttribute("summary", TradePortfolioSummary.from(holdings));
        return "trades/list";
    }

    private String showDetail(long id, Model model) {
        var history = service.history(id);
        model.addAttribute("history", history);
        addDateRange(dates.newTradeRange(history.holding().code()), model);
        return "trades/detail";
    }

    private void addDateRange(TradeDateRules.Range range, Model model) {
        model.addAttribute("minTradeDate", range.minDate());
        model.addAttribute("maxTradeDate", range.maxDate());
        model.addAttribute("firstListingDate", range.firstListingDate());
    }
}
