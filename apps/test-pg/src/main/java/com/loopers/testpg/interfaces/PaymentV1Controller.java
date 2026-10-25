package com.loopers.testpg.interfaces;

import com.loopers.testpg.application.CreateResult;
import com.loopers.testpg.application.LookupScenarioService;
import com.loopers.testpg.application.PaymentService;
import com.loopers.testpg.domain.Payment;
import com.loopers.testpg.domain.LookupScenario;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentV1Controller {
    private final PaymentService service;
    private final LookupScenarioService lookupScenario;
    private final String providerAccountId;

    public PaymentV1Controller(PaymentService service, LookupScenarioService lookupScenario,
                               @Value("${test-pg.provider-account-id}") String providerAccountId) {
        this.service = service;
        this.lookupScenario = lookupScenario;
        this.providerAccountId = providerAccountId;
    }

    @PostMapping
    public ResponseEntity<?> create(
        @RequestHeader("X-USER-ID") String accountId, @RequestBody PaymentV1Dto.CreateRequest request
    ) {
        verifyAccount(accountId);
        if (request.cardType() == null || request.cardType().isBlank()
            || request.cardNo() == null || request.cardNo().isBlank()
            || request.amount() == null || request.callbackUrl() == null) {
            throw new IllegalArgumentException("결제 입력이 올바르지 않습니다.");
        }
        var result = service.create(request.orderId(), request.amount(), URI.create(request.callbackUrl()));
        return switch (result) {
            case CreateResult.Accepted accepted -> {
                delay(accepted.responseDelay());
                yield ResponseEntity.accepted().body(PaymentV1Dto.PaymentResponse.from(accepted.payment()));
            }
            case CreateResult.RejectedBeforeCreate rejected -> ResponseEntity.unprocessableEntity()
                .contentType(MediaType.APPLICATION_JSON)
                .body(PaymentV1Dto.RejectedResponse.from(rejected));
            case CreateResult.Overridden overridden -> ResponseEntity.status(overridden.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(overridden.body());
        };
    }

    @GetMapping("/{transactionKey}")
    public PaymentV1Dto.PaymentResponse findByTransactionKey(
        @RequestHeader("X-USER-ID") String accountId, @PathVariable String transactionKey
    ) {
        verifyAccount(accountId);
        LookupScenario responseScenario = lookupScenario.nextResponse();
        var payment = service.findByTransactionKey(transactionKey);
        applyLookupScenario(responseScenario);
        return payment.map(PaymentV1Dto.PaymentResponse::from)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND"));
    }

    @GetMapping
    public List<PaymentV1Dto.PaymentResponse> findByOrderId(
        @RequestHeader("X-USER-ID") String accountId, @RequestParam String orderId
    ) {
        verifyAccount(accountId);
        if (!Payment.isValidOrderId(orderId)) {
            throw new IllegalArgumentException("주문 ID가 올바르지 않습니다.");
        }
        LookupScenario responseScenario = lookupScenario.nextResponse();
        var payments = service.findAllByOrderId(orderId);
        applyLookupScenario(responseScenario);
        return payments.stream().map(PaymentV1Dto.PaymentResponse::from).toList();
    }

    private static void applyLookupScenario(LookupScenario scenario) {
        delay(Duration.ofMillis(scenario.delayMillis()));
        if (scenario.failureCount() > 0) {
            throw new ResponseStatusException(HttpStatus.valueOf(scenario.failureStatus()), "TEST_PG_LOOKUP_FAILURE");
        }
    }

    // 생성 응답 유실과 조회 지연 시나리오에서 HTTP 응답만 늦춘다.
    private static void delay(Duration responseDelay) {
        if (responseDelay.isZero()) {
            return;
        }
        try {
            Thread.sleep(responseDelay);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void verifyAccount(String accountId) {
        if (!providerAccountId.equals(accountId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED_PROVIDER");
        }
    }
}
