package com.loopers.testpg.interfaces;

import com.loopers.testpg.application.PaymentService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

// 로컬 실행과 테스트에서만 켜는 시나리오 제어 API다. 기본 설정에서는 등록하지 않는다.
@RestController
@RequestMapping("/test-control/v1/orders/{orderId}")
@ConditionalOnProperty(prefix = "test-pg.control", name = "enabled", havingValue = "true")
public class TestControlV1Controller {
    private final PaymentService service;
    private final String providerAccountId;

    public TestControlV1Controller(PaymentService service,
                                   @Value("${test-pg.provider-account-id}") String providerAccountId) {
        this.service = service;
        this.providerAccountId = providerAccountId;
    }

    @PutMapping("/scenario")
    public ResponseEntity<Void> registerScenario(
        @RequestHeader("X-USER-ID") String accountId, @PathVariable String orderId,
        @RequestBody TestControlV1Dto.ScenarioRequest request
    ) {
        verifyAccount(accountId);
        service.registerScenario(orderId, request.toSetting());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/complete")
    public TestControlV1Dto.CompleteResponse complete(
        @RequestHeader("X-USER-ID") String accountId, @PathVariable String orderId
    ) {
        verifyAccount(accountId);
        return new TestControlV1Dto.CompleteResponse(service.completeOrder(orderId));
    }

    @GetMapping("/stats")
    public TestControlV1Dto.StatsResponse stats(
        @RequestHeader("X-USER-ID") String accountId, @PathVariable String orderId
    ) {
        verifyAccount(accountId);
        return TestControlV1Dto.StatsResponse.from(service.stats(orderId));
    }

    private void verifyAccount(String accountId) {
        if (!providerAccountId.equals(accountId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED_PROVIDER");
        }
    }
}
