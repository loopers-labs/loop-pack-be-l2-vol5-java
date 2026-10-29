package com.loopers.interfaces.api.pg;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/payments")
public class LocalPgController {

    private static final String PROVIDER_USER_ID = "135135";
    private static final int TIMEOUT_DELAY_MS = 30_000;
    private static final String REJECTED_CODE = "TEST_PG_REJECTED_BEFORE_CREATE";

    private final LocalPgStore store;

    /**
     * 결제 생성 요청.
     * - REJECTED 시나리오 (amount==1): 422 반환, 거래 미생성
     * - TIMEOUT 시나리오 (amount==2): 거래 저장 후 30초 지연 (호출자 타임아웃 유도)
     * - 그 외: 202 PENDING 즉시 반환, GET 조회 시 최종 상태 반환
     */
    @PostMapping
    public ResponseEntity<?> pay(
        @RequestHeader(value = "X-USER-ID", required = false) String userId,
        @RequestBody LocalPgDto.PaymentRequest request
    ) throws InterruptedException {
        if (!PROVIDER_USER_ID.equals(userId)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        if (store.isRejected(request.amount())) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new LocalPgDto.RejectedResponse(
                    REJECTED_CODE, request.orderId(), request.amount(), false
                ));
        }

        LocalPgDto.PaymentResponse stored = store.store(request.orderId(), request.amount());

        if (store.isTimeout(request.amount())) {
            Thread.sleep(TIMEOUT_DELAY_MS);
        }

        LocalPgDto.PaymentResponse pendingResponse = new LocalPgDto.PaymentResponse(
            stored.transactionKey(), stored.orderId(), stored.amount(), "PENDING"
        );
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(pendingResponse);
    }

    /**
     * 거래 단건 조회 (대사용).
     * GET으로 조회하면 최종 상태 반환 (SUCCESS 또는 FAILED).
     */
    @GetMapping("/{transactionKey}")
    public ResponseEntity<LocalPgDto.PaymentResponse> getByTransactionKey(
        @RequestHeader(value = "X-USER-ID", required = false) String userId,
        @PathVariable String transactionKey
    ) {
        if (!PROVIDER_USER_ID.equals(userId)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return store.findByTransactionKey(transactionKey)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 주문별 거래 목록 조회.
     * orderId로 조회하면 해당 주문의 거래 목록 반환. 빈 목록도 허용.
     */
    @GetMapping
    public ResponseEntity<List<LocalPgDto.PaymentResponse>> getByOrderId(
        @RequestHeader(value = "X-USER-ID", required = false) String userId,
        @RequestParam String orderId
    ) {
        if (!PROVIDER_USER_ID.equals(userId)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(store.findByOrderId(orderId));
    }
}
