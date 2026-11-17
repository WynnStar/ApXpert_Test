package tw.holdings.holding;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v2/holdings", produces = MediaType.APPLICATION_JSON_VALUE)
class TradeApiController {
    private final TradeHoldingService service;

    TradeApiController(TradeHoldingService service) {
        this.service = service;
    }

    @GetMapping
    List<TradeHoldingView> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    TradeHoldingView get(@PathVariable long id) {
        return service.history(id).holding();
    }

    @GetMapping("/{id}/trades")
    List<TradeHoldingView.TradeDetail> trades(@PathVariable long id) {
        return service.history(id).trades();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    TradeHoldingView create(@Valid @RequestBody TradeInput request) {
        return get(service.create(request.stockCode(), request.firstBuy()));
    }

    @PostMapping("/{id}/trades")
    @ResponseStatus(HttpStatus.CREATED)
    TradeHoldingView append(@PathVariable long id, @Valid @RequestBody TradeInput request) {
        service.append(id, request.trade(), request.confirmed());
        return get(id);
    }
}
