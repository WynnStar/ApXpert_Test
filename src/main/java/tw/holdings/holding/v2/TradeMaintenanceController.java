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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tw.holdings.holding.error.HoldingValidationException;
import tw.holdings.holding.error.HoldingVersionConflict;
import tw.holdings.trade.InsufficientSharesException;
import tw.holdings.trade.TradeLedgerService.Direction;
import tw.holdings.trade.TradeOrderException;

@Controller
@RequestMapping("/v2/holdings")
class TradeMaintenanceController {
    private final TradeHoldingService commands;
    private final TradeHoldingQueryService queries;
    private final TradeDateRules dates;

    TradeMaintenanceController(TradeHoldingService commands, TradeHoldingQueryService queries, TradeDateRules dates) {
        this.commands = commands;
        this.queries = queries;
        this.dates = dates;
    }

    @GetMapping("/{id}/delete")
    String confirmDelete(@PathVariable long id, Model model) {
        model.addAttribute("holding", queries.deleteTarget(id));
        return "trades/delete";
    }

    @PostMapping("/{id}/delete")
    String delete(@PathVariable long id, RedirectAttributes redirect) {
        commands.delete(id);
        redirect.addFlashAttribute("notice", "已刪除整檔持股及交易紀錄");
        return "redirect:/v2/holdings";
    }

    @GetMapping("/{id}/trades/{tradeId}/edit")
    String editTrade(@PathVariable long id, @PathVariable long tradeId, Model model) {
        var target = queries.tradeTarget(id, tradeId);
        model.addAttribute("tradeEdit", TradeEditForm.from(target.entry().trade()));
        return showTradeEdit(target, model);
    }

    @PostMapping("/{id}/trades/{tradeId}/edit")
    String updateTrade(@PathVariable long id, @PathVariable long tradeId,
                       @ModelAttribute("tradeEdit") @Valid TradeEditForm form, BindingResult errors,
                       Model model, RedirectAttributes redirect) {
        if (!errors.hasErrors()) {
            try {
                commands.editTrade(id, tradeId, form);
                redirect.addFlashAttribute("notice", "已修改交易紀錄");
                return "redirect:/v2/holdings/" + id;
            } catch (HoldingValidationException error) {
                errors.reject("tradeEdit", error.getMessage());
            } catch (HoldingVersionConflict error) {
                errors.rejectValue("confirmV2", "confirmV2", "請先確認由 2.0 管理這檔持股");
            } catch (InsufficientSharesException error) {
                errors.reject("tradeEdit", "無法修改。修改後會發生超賣：" + error.getMessage());
            }
        }
        return showTradeEdit(queries.tradeTarget(id, tradeId), model);
    }

    private String showTradeEdit(TradeHoldingQueryService.TradeTarget target, Model model) {
        model.addAttribute("target", target);
        var range = dates.range(target.holding().code());
        model.addAttribute("tradeDateRange", range);
        LocalDate originalDate = target.entry().trade().tradeDate();
        model.addAttribute("originalTradeDate", originalDate);
        model.addAttribute("tradeDateLocked", dates.editDateLocked(originalDate, range.maxDate()));
        return "trades/edit-trade";
    }

    @GetMapping("/{id}/trades/{tradeId}/delete")
    String confirmTradeDelete(@PathVariable long id, @PathVariable long tradeId, Model model) {
        model.addAttribute("target", queries.tradeTarget(id, tradeId));
        return "trades/delete-trade";
    }

    @PostMapping("/{id}/trades/{tradeId}/delete")
    String deleteTrade(@PathVariable long id, @PathVariable long tradeId, @RequestParam boolean lastTrade,
                       RedirectAttributes redirect) {
        String detail = "redirect:/v2/holdings/" + id;
        try {
            return switch (commands.deleteTrade(id, tradeId, lastTrade)) {
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
            commands.move(id, tradeId, direction);
            redirect.addFlashAttribute("notice", "已調整同日交易順序");
        } catch (TradeOrderException error) {
            redirect.addFlashAttribute("orderError", error.getMessage());
        } catch (InsufficientSharesException error) {
            redirect.addFlashAttribute("orderError", "無法調整順序。調整後會發生超賣：" + error.getMessage());
        }
        return "redirect:/v2/holdings/" + id;
    }
}
