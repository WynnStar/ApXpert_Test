package tw.holdings.holding;

import java.util.List;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
class HoldingApiController {
    private final HoldingService service;
    private final Validator validator;

    HoldingApiController(HoldingService service, Validator validator) {
        this.service = service;
        this.validator = validator;
    }

    @GetMapping("/holdings")
    List<HoldingApiResponse> list() {
        return service.list().stream().map(HoldingApiResponse::from).toList();
    }

    @GetMapping("/holdings/{id}")
    HoldingApiResponse get(@PathVariable long id) {
        return HoldingApiResponse.from(service.withQuote(service.get(id)));
    }

    @PostMapping("/holdings")
    @ResponseStatus(HttpStatus.CREATED)
    HoldingApiResponse create(@RequestBody HoldingApiRequest request) {
        CreateHoldingForm form = new CreateHoldingForm(request.code(), request.terms());
        validate(form);
        var holding = service.create(form.code(), form.lots(), form.buyPrice());
        return HoldingApiResponse.from(service.withQuote(holding));
    }

    @PutMapping("/holdings/{id}")
    HoldingApiResponse update(@PathVariable long id, @RequestBody HoldingApiRequest request) {
        if (request.code() != null) {
            throw new HoldingValidationException("修改持股時不能變更股票代號");
        }
        HoldingTermsForm terms = request.terms();
        validate(terms);
        HoldingDetails existing = service.get(id);
        if (existing.name() == null || existing.market() == null) {
            HoldingApiResponse.from(service.withQuote(existing));
        }
        return HoldingApiResponse.from(service.withQuote(service.update(id, terms.lots(), terms.buyPrice())));
    }

    @DeleteMapping("/holdings/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable long id) {
        service.delete(id);
    }

    @GetMapping("/summary")
    SummaryApiResponse summary() {
        return SummaryApiResponse.from(service.portfolio().summary());
    }

    private void validate(Object form) {
        validator.validate(form).stream()
                .map(ConstraintViolation::getMessage)
                .sorted()
                .findFirst()
                .ifPresent(message -> { throw new HoldingValidationException(message); });
    }
}
