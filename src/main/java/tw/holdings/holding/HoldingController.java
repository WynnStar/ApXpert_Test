package tw.holdings.holding;

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

@Controller
@RequestMapping("/holdings")
class HoldingController {
    private final HoldingService service;

    HoldingController(HoldingService service) {
        this.service = service;
    }

    @GetMapping
    String list(Model model) {
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", CreateHoldingForm.empty());
        }
        return showList(model);
    }

    @PostMapping
    String create(@Valid @ModelAttribute("form") CreateHoldingForm form,
                  BindingResult errors, RedirectAttributes redirect) {
        if (errors.hasErrors()) {
            return redirectAfterCreateError(form, errors, redirect);
        }
        try {
            service.create(form.code(), form.lots(), form.buyPrice());
        } catch (DuplicateHoldingException exception) {
            errors.rejectValue("code", "duplicate", exception.getMessage());
            return redirectAfterCreateError(form, errors, redirect);
        } catch (StockLookupException exception) {
            errors.rejectValue("code", "stockLookup", exception.getMessage());
            return redirectAfterCreateError(form, errors, redirect);
        }
        redirect.addFlashAttribute("notice", "已新增持股");
        return "redirect:/holdings";
    }

    private String redirectAfterCreateError(CreateHoldingForm form, BindingResult errors,
                                            RedirectAttributes redirect) {
        redirect.addFlashAttribute("form", form);
        redirect.addFlashAttribute(BindingResult.MODEL_KEY_PREFIX + "form", errors);
        return "redirect:/holdings";
    }

    @GetMapping("/{id}/edit")
    String edit(@PathVariable long id, Model model) {
        HoldingDetails holding = service.get(id);
        model.addAttribute("holding", holding);
        model.addAttribute("form", new HoldingTermsForm(holding.lots(), holding.buyPrice()));
        return "holdings/edit";
    }

    @PostMapping("/{id}/edit")
    String update(@PathVariable long id, @Valid @ModelAttribute("form") HoldingTermsForm form,
                  BindingResult errors, Model model, RedirectAttributes redirect) {
        if (errors.hasErrors()) {
            model.addAttribute("holding", service.get(id));
            return "holdings/edit";
        }
        service.update(id, form.lots(), form.buyPrice());
        redirect.addFlashAttribute("notice", "已修改持股");
        return "redirect:/holdings";
    }

    @PostMapping("/{id}/delete")
    String delete(@PathVariable long id, RedirectAttributes redirect) {
        service.delete(id);
        redirect.addFlashAttribute("notice", "已刪除持股");
        return "redirect:/holdings";
    }

    @ExceptionHandler(HoldingNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    String notFound(HoldingNotFoundException exception, Model model) {
        model.addAttribute("message", exception.getMessage());
        return "holdings/not-found";
    }

    private String showList(Model model) {
        model.addAttribute("holdings", service.list());
        return "holdings/list";
    }
}
