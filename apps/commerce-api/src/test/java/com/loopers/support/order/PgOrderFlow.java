package com.loopers.support.order;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.loopers.application.order.ConfirmPaymentCommand;
import com.loopers.application.order.OrderConfirmedPayload;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.OrderInfo;
import com.loopers.application.order.OrderItemRequest;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.support.pg.TestPg;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

/**
 * 실제 주문·결제 경로로 PG 주문을 만들고 확정하는 테스트 도구(docs/week7/decisions.md INV-701). 상태 컬럼을 SQL로 쓰지 않는다.
 * 흐름은 OutboxRelayTest·OrderConfirmedOutboxAtomicityTest와 같다: 새 사용자·새 상품으로 주문(DRAFT) → 결제 확인(테스트 PG 기본
 * 202 PENDING) → 같은 거래 조회 SUCCESS 반영(확정 TX가 Outbox 행을 함께 저장).
 *
 * <p>Spring 빈이 아니다 — 테스트 소스의 {@code @Component}는 모든 테스트 컨텍스트에 끼어든다. 테스트가 빈을 받아 직접 만든다.
 */
public final class PgOrderFlow {

    public static final long PRICE = 1_000L;
    public static final int INITIAL_STOCK = 10;

    private final OrderFacade orderFacade;
    private final UserJpaRepository userJpaRepository;
    private final ProductJpaRepository productJpaRepository;
    private final WireMockServer pg = TestPg.server();

    public PgOrderFlow(OrderFacade orderFacade, UserJpaRepository userJpaRepository, ProductJpaRepository productJpaRepository) {
        this.orderFacade = orderFacade;
        this.userJpaRepository = userJpaRepository;
        this.productJpaRepository = productJpaRepository;
    }

    /**
     * 한 주문. quantityByProductId는 주문에 넣은 순서(상품 A, B, …)를 지킨다.
     */
    public record PlacedOrder(Long userId, Long orderId, Map<Long, Integer> quantityByProductId) {

        public PlacedOrder {
            quantityByProductId = Collections.unmodifiableMap(new LinkedHashMap<>(quantityByProductId));
        }

        public List<Long> productIds() {
            return List.copyOf(quantityByProductId.keySet());
        }

        public String providerOrderId() {
            return String.format("LP-ORD-%010d", orderId);
        }

        public String transactionKey() {
            return TestPg.transactionKeyOf(providerOrderId());
        }

        public long amount() {
            return quantityByProductId.values().stream().mapToLong(quantity -> quantity * PRICE).sum();
        }

        public String eventId() {
            return OrderConfirmedPayload.eventIdOf(orderId);
        }

        public String messageKey() {
            return String.valueOf(orderId);
        }
    }

    /** 새 사용자(포인트 userPoints)와 수량마다 새 상품(재고 {@link #INITIAL_STOCK})으로 DRAFT 주문을 만든다. */
    public PlacedOrder draft(long userPoints, int... quantities) {
        UserModel user = new UserModel();
        if (userPoints > 0) {
            user.getPoint().charge(userPoints);
        }
        Long userId = userJpaRepository.save(user).getId();
        Map<Long, Integer> quantityByProductId = new LinkedHashMap<>();
        List<OrderItemRequest> items = new ArrayList<>();
        for (int quantity : quantities) {
            Long productId = productJpaRepository.save(new ProductModel("상품", PRICE, 1L, INITIAL_STOCK)).getId();
            quantityByProductId.put(productId, quantity);
            items.add(new OrderItemRequest(productId, quantity));
        }
        Long orderId = orderFacade.createOrder(userId, items).id();
        return new PlacedOrder(userId, orderId, quantityByProductId);
    }

    /** 결제 확인 — 재고를 예약하고 PG에 거래를 한 번 만든다(테스트 PG 기본 응답 202 PENDING). */
    public OrderInfo startPayment(PlacedOrder order) {
        return orderFacade.confirmOrder(order.orderId(), order.userId(),
            new ConfirmPaymentCommand("key-" + order.orderId(), "TEST", "0000-0000-0000-0000"));
    }

    /** 처리중 결제를 같은 거래 조회 SUCCESS로 반영한다 — 5주차 "같은 거래 조회 → 결과 적용" 경로, 확정 TX다. */
    public OrderInfo applySuccess(PlacedOrder order) {
        pg.stubFor(get(urlEqualTo(TestPg.PAYMENTS_PATH + "/" + order.transactionKey()))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"transactionKey\":\"" + order.transactionKey() + "\",\"orderId\":\"" + order.providerOrderId()
                    + "\",\"amount\":" + order.amount() + ",\"status\":\"SUCCESS\"}")));
        return orderFacade.reconcilePayment(order.orderId(), order.userId());
    }

    /** 테스트 PG가 받은 거래 생성 요청 수. */
    public int pgCreates() {
        return pg.findAll(postRequestedFor(urlEqualTo(TestPg.PAYMENTS_PATH))).size();
    }

    /** 테스트 PG가 받은 모든 요청 수(생성·조회). */
    public int pgRequests() {
        return pg.getAllServeEvents().size();
    }
}
