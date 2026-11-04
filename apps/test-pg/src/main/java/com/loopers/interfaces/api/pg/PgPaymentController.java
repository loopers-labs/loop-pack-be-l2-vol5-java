package com.loopers.interfaces.api.pg;

import com.loopers.application.pg.command.CreatePgPaymentCommand;
import com.loopers.application.pg.query.PgLookupView;
import com.loopers.application.pg.query.PgPaymentQueryDao;
import com.loopers.application.pg.query.PgPaymentView;
import com.loopers.application.pg.result.CreatePgPaymentResult;
import com.loopers.application.pg.usecase.CreatePgPaymentUseCase;
import com.loopers.domain.pg.model.PgScenario;
import com.loopers.domain.pg.model.PgTransaction;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PgPaymentController {
    private final CreatePgPaymentUseCase createPayment;
    private final PgPaymentQueryDao queryDao;
    private final PgResponseDispatcher dispatcher;
    private final URI pgCallbackUri;

    @PostMapping
    public DeferredResult<ResponseEntity<?>> create(@Valid @RequestBody CreatePgPaymentRequest request) {
        if (!pgCallbackUri.toString().equals(request.callbackUrl())) {
            throw new IllegalArgumentException("callback 주소는 PG 설정과 일치해야 합니다.");
        }
        PgScenario scenario = request.scenario();
        CreatePgPaymentResult result = createPayment.execute(
                new CreatePgPaymentCommand(request.orderId(), request.amount(), request.callbackUrl(), scenario));
        if (result.rejected()) {
            return dispatcher.dispatch(List.of(), ResponseEntity.status(422).body(
                    new Rejection("TEST_PG_REJECTED_BEFORE_CREATE", request.orderId(), request.amount(), false)));
        }
        PgTransaction transaction = result.transaction();
        PgPaymentView payment = new PgPaymentView(transaction.transactionKey(), transaction.orderId(),
                transaction.amount(), transaction.status().name());
        return dispatcher.dispatch(List.of(new PgResponseDispatcher.Condition(
                scenario.createResponseMode(), scenario.createResponseDelayMs())), ResponseEntity.accepted().body(payment));
    }

    @GetMapping("/{transactionKey}")
    public DeferredResult<ResponseEntity<?>> find(@PathVariable String transactionKey) {
        PgLookupView lookup = queryDao.findByKey(transactionKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return dispatcher.dispatch(List.of(condition(lookup)), ResponseEntity.ok(lookup.payment()));
    }

    @GetMapping
    public DeferredResult<ResponseEntity<?>> list(@RequestParam String orderId) {
        List<PgLookupView> lookups = queryDao.findByOrderId(orderId);
        return dispatcher.dispatch(lookups.stream().map(PgPaymentController::condition).toList(),
                ResponseEntity.ok(lookups.stream().map(PgLookupView::payment).toList()));
    }

    private static PgResponseDispatcher.Condition condition(PgLookupView lookup) {
        return new PgResponseDispatcher.Condition(lookup.responseMode(), lookup.responseDelayMs());
    }

    public record Rejection(String code, String orderId, long amount, boolean transactionCreated) {}
}
