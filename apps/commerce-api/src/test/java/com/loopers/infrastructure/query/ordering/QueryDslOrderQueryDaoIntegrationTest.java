package com.loopers.infrastructure.query.ordering;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.application.common.PageCriteria;
import com.loopers.application.common.PageResult;
import com.loopers.application.ordering.query.AdminOrderView;
import com.loopers.application.ordering.query.OrderQueryDao;
import com.loopers.application.ordering.query.OrderView;
import com.loopers.domain.ordering.model.Order;
import com.loopers.domain.ordering.model.OrderItem;
import com.loopers.domain.ordering.model.OrderRecord;
import com.loopers.domain.ordering.model.OrderStatus;
import com.loopers.domain.ordering.model.PaymentMethod;
import com.loopers.domain.ordering.repository.OrderRepository;
import com.loopers.domain.pay.model.PaymentAttempt;
import com.loopers.domain.pay.model.PaymentRecoveryTask;
import com.loopers.domain.pay.model.PaymentScenario;
import com.loopers.domain.pay.repository.PaymentAttemptRepository;
import com.loopers.domain.pay.repository.PaymentRecoveryTaskRepository;
import com.loopers.infrastructure.persistence.ordering.entity.QOrderJpaEntity;
import com.loopers.support.test.IntegrationTest;
import com.loopers.utils.DatabaseCleanUp;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
class QueryDslOrderQueryDaoIntegrationTest {
    @Autowired private PaymentAttemptRepository attempts;
    @Autowired private PaymentRecoveryTaskRepository tasks;
    private static final QOrderJpaEntity ORDER = QOrderJpaEntity.orderJpaEntity;

    @Autowired
    private OrderQueryDao orderQueryDao;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private JPAQueryFactory queryFactory;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("내 주문 목록")
    @Test
    void returnsOrders_orderedByCreatedAtDescending() {
        Order older = orderRepository.insert(order(1L));
        Order newer = orderRepository.insert(order(1L));
        setCreatedAt(older.getId(), "2026-01-01T00:00:00Z");
        setCreatedAt(newer.getId(), "2026-01-02T00:00:00Z");

        PageResult<OrderView> result = orderQueryDao.findOrders(1L, new PageCriteria(0, 20));

        assertThat(result.totalElements()).isEqualTo(2);
        assertThat(result.items()).extracting(OrderView::orderId).containsExactly(newer.getId(), older.getId());
        OrderView view = result.items().get(0);
        assertThat(view.items()).hasSize(1);
        assertThat(view.items().get(0).productName()).isEqualTo("상품");
        assertThat(view.paymentAmount()).isNull();
        assertThat(view.paymentStatus()).isNull();
    }

    @DisplayName("다른 사용자의 주문은 목록에서 제외한다")
    @Test
    void excludesOtherUsersOrders() {
        orderRepository.insert(order(1L));
        orderRepository.insert(order(2L));

        PageResult<OrderView> result = orderQueryDao.findOrders(1L, new PageCriteria(0, 20));

        assertThat(result.totalElements()).isEqualTo(1);
    }

    @DisplayName("결제 완료된 주문은 결제 금액·상태를 함께 반환한다")
    @Test
    void includesPaymentFields_whenOrderRecordExists() {
        Order legacy = order(1L);
        legacy.confirm();
        Order saved = orderRepository.insert(legacy);

        OrderView view = orderQueryDao.findOrder(1L, saved.getId()).orElseThrow();

        assertThat(view.paymentAmount()).isEqualTo(saved.getTotalAmount());
        assertThat(view.paymentStatus().name()).isEqualTo("PAID");
        assertThat(view.paymentMethod()).isEqualTo(PaymentMethod.POINT);
        assertThat(view.paymentAttempt()).isNull();
        assertThat(orderQueryDao.findOrder(2L, saved.getId())).isEmpty();
    }

    @DisplayName("없는 주문은 빈 결과를 반환한다")
    @Test
    void returnsEmpty_whenOrderDoesNotExist() {
        Optional<OrderView> result = orderQueryDao.findOrder(1L, 999L);

        assertThat(result).isEmpty();
    }

    @DisplayName("관리자 주문 목록은 모든 구매자의 주문에 구매자 ID를 포함한다")
    @Test
    void returnsAllUsersOrders_withUserId_forAdmin() {
        orderRepository.insert(order(1L));
        orderRepository.insert(order(2L));

        PageResult<AdminOrderView> result = orderQueryDao.findAdminOrders(new PageCriteria(0, 20));

        assertThat(result.totalElements()).isEqualTo(2);
        assertThat(result.items()).extracting(AdminOrderView::userId).containsExactlyInAnyOrder(1L, 2L);
    }

    private Order order(long userId) {
        return Order.create(userId, List.of(OrderItem.create(1L, "상품", 1_000L, 2)));
    }

    @Test
    @DisplayName("PG 대기의 결제 사실과 복구 진행을 고객·관리자 단건/목록에서 같은 값으로 읽는다")
    void projectsPgAttemptAndProgressWithoutPaidRecord() {
        long orderId = pgOrder(PaymentRecoveryTask.State.RUNNING, false, false);
        OrderView view = orderQueryDao.findOrder(2L, orderId).orElseThrow();
        assertThat(view.paymentMethod()).isEqualTo(PaymentMethod.PG);
        assertThat(view.paymentAmount()).isNull();
        assertThat(view.paymentStatus()).isNull();
        assertThat(view.paymentAttempt().outcome()).isEqualTo(PaymentAttempt.Outcome.PENDING);
        assertThat(view.paymentAttempt().recoveryState()).isEqualTo(PaymentRecoveryTask.State.RUNNING);
        assertThat(view.paymentAttempt().usedRounds()).isEqualTo(1);
        assertThat(view.paymentAttempt().lastCheckedAt()).isNotNull();
        assertThat(view.paymentLookupUrl()).isEqualTo("/api/v1/orders/" + orderId);
        assertThat(view.attentionRequired()).isFalse();
        assertThat(orderQueryDao.findOrder(1L, orderId)).isEmpty();
        assertThat(orderQueryDao.findOrders(2L, new PageCriteria(0, 20)).items().get(0).paymentAttempt())
            .isEqualTo(view.paymentAttempt());
        assertThat(orderQueryDao.findAdminOrder(orderId).orElseThrow().paymentAttempt()).isEqualTo(view.paymentAttempt());
        assertThat(orderQueryDao.findAdminOrder(orderId).orElseThrow().paymentLookupUrl())
            .isEqualTo("/api-admin/v1/orders/" + orderId);
        assertThat(orderQueryDao.findAdminOrders(new PageCriteria(0, 20)).items().get(0).paymentMethod())
            .isEqualTo(PaymentMethod.PG);
    }

    @Test
    @DisplayName("미완료 목록은 대기·수동·완료 후 충돌을 포함하고 정상 완료/POINT/DRAFT는 제외하며 페이지 총수를 유지한다")
    void listsPendingManualAndLateConflictWithStablePagination() {
        long pending = pgOrder(PaymentRecoveryTask.State.READY, false, false);
        long manual = pgOrder(PaymentRecoveryTask.State.MANUAL_REQUIRED, false, false);
        long conflict = pgOrder(PaymentRecoveryTask.State.APPLIED, true, true);
        pgOrder(PaymentRecoveryTask.State.APPLIED, true, false);
        Order point = order(1L);
        point.confirm();
        orderRepository.insert(point);
        orderRepository.insert(order(2L));
        setCreatedAt(pending, "2026-01-01T00:00:00Z");
        setCreatedAt(manual, "2026-01-01T00:00:00Z");
        setCreatedAt(conflict, "2026-01-01T00:00:00Z");
        PageResult<AdminOrderView> first = orderQueryDao.findIncompletePayments(new PageCriteria(0, 2));
        PageResult<AdminOrderView> second = orderQueryDao.findIncompletePayments(new PageCriteria(1, 2));
        assertThat(first.totalElements()).isEqualTo(3);
        assertThat(second.totalElements()).isEqualTo(3);
        assertThat(first.items()).extracting(AdminOrderView::orderId).containsExactly(conflict, manual);
        assertThat(second.items()).extracting(AdminOrderView::orderId).containsExactly(pending);
        assertThat(first.items()).allSatisfy(view -> assertThat(view.attentionRequired()).isTrue());
        assertThat(first.items().get(0).status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(first.items().get(0).paymentAttempt().recoveryState()).isEqualTo(PaymentRecoveryTask.State.APPLIED);
        assertThat(first.items().get(0).items()).hasSize(1);
    }

    private long pgOrder(PaymentRecoveryTask.State state, boolean completed, boolean conflict) {
        return transactionTemplate.execute(status -> {
            Order saved = orderRepository.insert(order(2L));
            orderRepository.prepareIfDraft(saved.getId(), 2L);
            PaymentAttempt attempt = PaymentAttempt.create(saved.getId(), UUID.randomUUID().toString(),
                saved.getTotalAmount(), PaymentScenario.resolve(null, null)).startDispatch();
            attempt = attempt.observe(attempt.getProviderOrderId(), attempt.getAmount(), "tx-" + saved.getId(),
                completed ? PaymentAttempt.Outcome.SUCCESS : PaymentAttempt.Outcome.PENDING);
            if (conflict) { attempt = attempt.noteConflict(PaymentAttempt.Conflict.TERMINAL_CONFLICT); }
            attempts.insert(attempt);
            boolean started = state == PaymentRecoveryTask.State.RUNNING || state == PaymentRecoveryTask.State.MANUAL_REQUIRED;
            tasks.insert(PaymentRecoveryTask.restore(attempt.getAttemptKey(), state,
                started ? UUID.randomUUID().toString() : null, started ? 1 : 0, started ? Instant.now() : null,
                state == PaymentRecoveryTask.State.MANUAL_REQUIRED ? PaymentRecoveryTask.Reason.APPLICATION_FAILED
                    : PaymentRecoveryTask.Reason.NONE, Instant.now()));
            if (completed) {
                orderRepository.completePgIfPending(saved.getId(), OrderStatus.CONFIRMED,
                    OrderRecord.paidByPg(2L, saved.getTotalAmount()));
            }
            return saved.getId();
        });
    }

    private void setCreatedAt(long orderId, String createdAt) {
        transactionTemplate.executeWithoutResult(status -> queryFactory.update(ORDER)
            .set(ORDER.createdAt, Instant.parse(createdAt))
            .where(ORDER.id.eq(orderId))
            .execute());
    }
}
