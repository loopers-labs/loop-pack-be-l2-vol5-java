package com.loopers.application.outbox;

import com.loopers.application.order.OrderFacade;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.outbox.OutboxEvent;
import com.loopers.domain.outbox.OutboxEventRepository;
import com.loopers.domain.outbox.OutboxStatus;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.order.PgOrderFlow;
import com.loopers.support.order.PgOrderFlow.PlacedOrder;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 복구 진입점을 실제 MySQL에 붙여 본다(docs/week7/decisions.md D-5, INV-706). 판단 자체는 OutboxBackfillTest가 DB 없이 본다 —
 * 여기서는 "이미 행이 있는 주문(주문 B와 같은 모양)은 늘리지도 바꾸지도 않는다"와 "사실이 모자라면 아무것도 쓰지 않는다",
 * 그리고 복구가 PG를 부르지 않는다는 것을 실제 저장소와 테스트 PG 요청 기록으로 확인한다.
 *
 * <p>행이 없는 CONFIRMED 주문(주문 A)은 7주차 코드로 만들 수 없다 — 확정이 늘 행을 함께 만들고, 행을 지우거나 DB에 직접 넣는 것은
 * 과제가 막는다(D-10). 그 갈래는 6주차 jar로 주문을 만드는 재현 E2E(D-14, check 밖)가 맡는다.
 *
 * <p>입력이 겹치지 않게 한다: 테스트 사이에 DB를 비우지 않아 주문·상품 ID가 클래스 안에서 겹치지 않는다. 검증은 자기 주문으로만 한다.
 * 테스트 PG 요청 기록은 메서드마다 TestPgResetListener가 비운다.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutboxBackfillIntegrationTest {

    @Autowired
    private OutboxBackfill outboxBackfill;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OrderFacade orderFacade;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private OrderJpaRepository orderJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private PgOrderFlow flow;

    @BeforeEach
    void setUp() {
        flow = new PgOrderFlow(orderFacade, userJpaRepository, productJpaRepository);
    }

    @AfterAll
    void cleanUp() {
        databaseCleanUp.truncateAllTables();
    }

    private PlacedOrder confirmed() {
        PlacedOrder order = flow.draft(0, 2, 1);
        flow.startPayment(order);
        assertThat(flow.applySuccess(order).status()).as("확정 응답").isEqualTo(OrderStatus.CONFIRMED);
        return order;
    }

    private List<OutboxEvent> rowsOf(PlacedOrder order) {
        return outboxEventRepository.findAllByOrderId(order.orderId());
    }

    private static String describe(OutboxEvent event) {
        return "OutboxEvent{id=" + event.getId() + ", eventId=" + event.getEventId() + ", status=" + event.getStatus()
            + ", attemptCount=" + event.getAttemptCount() + ", nextAttemptAt=" + event.getNextAttemptAt()
            + ", createdAt=" + event.getCreatedAt() + ", updatedAt=" + event.getUpdatedAt() + "}";
    }

    @DisplayName("이미 행이 있는 확정 주문(7주차 확정 경로): 복구는 ALREADY_PRESENT_SAME이고 행은 하나 그대로다 — payload·상태·시도·"
        + "다음 시도 시각이 바뀌지 않는다. 두 번 불러도 같다. 복구 동안 PG 요청은 하나도 없다. (D-5, INV-706)")
    @Test
    void confirmedOrderWithRow_isLeftUnchanged() {
        // arrange
        PlacedOrder order = confirmed();
        OutboxEvent before = rowsOf(order).get(0);
        int pgRequestsBefore = flow.pgRequests();

        // act
        OutboxBackfill.Result first = outboxBackfill.backfill(order.orderId());
        OutboxBackfill.Result second = outboxBackfill.backfill(order.orderId());

        // assert
        List<OutboxEvent> rows = rowsOf(order);
        assertThat(rows).hasSize(1);
        OutboxEvent after = rows.get(0);
        System.out.println("[Outbox 복구 — 이미 있음] " + first + ", " + second + " / " + describe(after));
        assertAll(
            () -> assertThat(first).isEqualTo(OutboxBackfill.Result.ALREADY_PRESENT_SAME),
            () -> assertThat(second).isEqualTo(OutboxBackfill.Result.ALREADY_PRESENT_SAME),
            () -> assertThat(after.getId()).isEqualTo(before.getId()),
            () -> assertThat(after.getEventId()).isEqualTo(order.eventId()),
            () -> assertThat(after.getPayload()).isEqualTo(before.getPayload()),
            () -> assertThat(after.getMessageKey()).isEqualTo(before.getMessageKey()),
            () -> assertThat(after.getStatus()).isEqualTo(OutboxStatus.PENDING),
            () -> assertThat(after.getAttemptCount()).isEqualTo(before.getAttemptCount()),
            () -> assertThat(after.getNextAttemptAt().toInstant()).isEqualTo(before.getNextAttemptAt().toInstant()),
            () -> assertThat(after.getUpdatedAt().toInstant()).isEqualTo(before.getUpdatedAt().toInstant()),
            () -> assertThat(flow.pgRequests()).as("복구 동안 PG 생성·조회 요청").isEqualTo(pgRequestsBefore),
            () -> assertThat(orderJpaRepository.findById(order.orderId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED)
        );
    }

    @DisplayName("확정되지 않은 주문(DRAFT, 결제 진행 중): 복구는 409로 거절하고 행을 만들지 않는다. PG 요청도 없다. (D-5 — 사실이 모자라면 추측하지 않는다)")
    @Test
    void notConfirmedOrder_isRejectedWithoutRow() {
        // arrange
        PlacedOrder draft = flow.draft(0, 2, 1);
        PlacedOrder paying = flow.draft(0, 2, 1);
        flow.startPayment(paying);
        assertThat(orderJpaRepository.findById(paying.orderId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        int pgRequestsBefore = flow.pgRequests();

        // act
        CoreException draftError = assertThrows(CoreException.class, () -> outboxBackfill.backfill(draft.orderId()));
        CoreException payingError = assertThrows(CoreException.class, () -> outboxBackfill.backfill(paying.orderId()));

        // assert
        assertAll(
            () -> assertThat(draftError.getErrorType()).isEqualTo(ErrorType.CONFLICT),
            () -> assertThat(draftError.getMessage()).contains("CONFIRMED가 아님", "confirmed_at"),
            () -> assertThat(payingError.getErrorType()).isEqualTo(ErrorType.CONFLICT),
            () -> assertThat(rowsOf(draft)).isEmpty(),
            () -> assertThat(rowsOf(paying)).isEmpty(),
            () -> assertThat(outboxEventRepository.findByEventId(draft.eventId())).isEmpty(),
            () -> assertThat(outboxEventRepository.findByEventId(paying.eventId())).isEmpty(),
            () -> assertThat(flow.pgRequests()).as("복구 동안 PG 생성·조회 요청").isEqualTo(pgRequestsBefore)
        );
    }

    @DisplayName("없는 주문: 복구는 404로 거절하고 행을 만들지 않는다.")
    @Test
    void missingOrder_isNotFound() {
        // arrange
        Long missingOrderId = flow.draft(0, 1).orderId() + 1_000_000;

        // act
        CoreException exception = assertThrows(CoreException.class, () -> outboxBackfill.backfill(missingOrderId));

        // assert
        assertAll(
            () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND),
            () -> assertThat(outboxEventRepository.findAllByOrderId(missingOrderId)).isEmpty()
        );
    }
}
