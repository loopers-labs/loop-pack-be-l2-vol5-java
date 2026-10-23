package com.loopers.application.payment;

import com.loopers.application.order.dto.OrderItemCommand;
import com.loopers.application.payment.dto.PaymentAttemptInfo;
import com.loopers.application.payment.dto.PaymentCheckInfo;
import com.loopers.domain.product.Product;
import com.loopers.domain.user.User;
import com.loopers.support.jpa.FailingStatementInspector.InjectedFailureException;
import com.loopers.support.jpa.FailingStatementInspector;
import com.loopers.support.pg.FakePgServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 결제 시작의 로컬 트랜잭션 A·B 가 각각 함께 commit 되거나 함께 취소되는지 실제 MySQL 로 확인한다. 트랜잭션이 전역 잠금
 * 순서(주문 → 상품)대로 행을 잠그고 포인트를 건드리지 않는지도 실행된 SQL 로 확인한다.
 *
 * <p>테스트를 부모 트랜잭션으로 감싸지 않는다. 데이터는 각각 commit 해 두고, 결과는 영속성 컨텍스트를 거치지 않는
 * {@code JdbcTemplate} 으로 새로 읽는다. 실패는 {@link FailingStatementInspector} 로 UPDATE 문이 DB 로 나가기 직전에
 * 주입하며, 운영 코드에는 실패용 스위치가 없다.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.session_factory.statement_inspector="
    + "com.loopers.support.jpa.FailingStatementInspector")
class PaymentTransactionTest extends PaymentFlowTestSupport {

    private static final Pattern FROM_TABLE = Pattern.compile("\\bfrom (\\w+)");
    private static final long USER_BALANCE = 10_000L;

    @AfterEach
    void disarmInspector() {
        FailingStatementInspector.disarm();
    }

    // `for update` 로 끝나는 조회가 어떤 테이블을 어떤 순서로 잠갔는지 돌려준다.
    private List<String> lockedTables() {
        return FailingStatementInspector.statements().stream()
            .filter(sql -> sql.endsWith("for update"))
            .map(sql -> {
                Matcher matcher = FROM_TABLE.matcher(sql);
                assertThat(matcher.find()).as(sql).isTrue();
                return matcher.group(1);
            })
            .toList();
    }

    @DisplayName("결제를 시작하는 트랜잭션 A에서,")
    @Nested
    class TransactionA {
        @DisplayName("재고 확보, 시도 기록, PAYMENT_PENDING 이 함께 commit 되고 포인트는 건드리지 않는다.")
        @Test
        void commitsStockAttemptAndStatus_together() {
            // arrange: 3,000 x 1 + 2,000 x 2 = 7,000
            User user = saveUser(USER_BALANCE);
            Product shirt = saveProduct("티셔츠", 3_000, 10);
            Product socks = saveProduct("양말", 2_000, 5);
            Long orderId = orderFacade.create(user.getId(), List.of(
                new OrderItemCommand(shirt.getId(), 1),
                new OrderItemCommand(socks.getId(), 2)
            )).id();

            // act: PG 호출 없이 트랜잭션 A 만 실행한다.
            orderFacade.prepareConfirm(command(user, orderId, "attempt-1"));

            // assert
            assertThat(orderStatusOf(orderId)).isEqualTo("PAYMENT_PENDING");
            assertThat(stockOf(shirt)).isEqualTo(9);
            assertThat(stockOf(socks)).isEqualTo(3);
            assertThat(attemptOf(orderId)).containsEntry("status", "REQUESTED").containsEntry("amount", 7_000L);
            assertThat(balanceOf(user)).isEqualTo(USER_BALANCE);
        }

        @DisplayName("주문 → 상품 순서로만 행을 잠그고 사용자는 잠그지 않는다.")
        @Test
        void locksOrderThenProducts_andNeverUser() {
            // arrange: 품목 순서와 무관하게 상품은 한 번의 조회로 id 오름차순 잠금을 건다.
            User user = saveUser(USER_BALANCE);
            Product shirt = saveProduct("티셔츠", 3_000, 10);
            Product socks = saveProduct("양말", 2_000, 5);
            Long orderId = orderFacade.create(user.getId(), List.of(
                new OrderItemCommand(socks.getId(), 2),
                new OrderItemCommand(shirt.getId(), 1)
            )).id();
            FailingStatementInspector.record();

            // act
            orderFacade.prepareConfirm(command(user, orderId, "attempt-1"));

            // assert
            assertThat(lockedTables()).containsExactly("orders", "products");
            assertThat(FailingStatementInspector.statements())
                .anyMatch(sql -> sql.contains("from products") && sql.endsWith("order by p1_0.id for update"));
        }

        @DisplayName("시도 기록과 주문 상태 변경이 DB 로 전송된 뒤 재고 확보가 실패하면 전부 변경 전으로 돌아가고 PG 를 호출하지 않는다.")
        @Test
        void rollsBackEverything_andDoesNotCallPg_whenStockUpdateFails() {
            // arrange
            User user = saveUser(USER_BALANCE);
            Product shirt = saveProduct("티셔츠", 3_000, 10);
            Product socks = saveProduct("양말", 2_000, 5);
            Long orderId = orderFacade.create(user.getId(), List.of(
                new OrderItemCommand(shirt.getId(), 1),
                new OrderItemCommand(socks.getId(), 2)
            )).id();
            FailingStatementInspector.arm("update products", 1);

            // act & assert: 실패는 밖으로 전달된다. Spring 이 감쌀 수 있으므로 원인 사슬에서 주입한 예외를 찾는다.
            assertThatThrownBy(() -> orderFacade.confirm(command(user, orderId, "attempt-1")))
                .hasRootCauseInstanceOf(InjectedFailureException.class);

            // 실패 전에 시도 insert 와 주문 update 가 실제로 DB 로 나갔어야 rollback 검증이 의미를 가진다.
            List<String> mutations = FailingStatementInspector.mutations();
            assertThat(mutations.get(mutations.size() - 1)).startsWith("update products");
            assertThat(mutations.subList(0, mutations.size() - 1))
                .anyMatch(sql -> sql.startsWith("insert into payment_attempts"))
                .anyMatch(sql -> sql.startsWith("update orders"));

            FailingStatementInspector.disarm();
            assertThat(orderStatusOf(orderId)).isEqualTo("DRAFT");
            assertThat(stockOf(shirt)).isEqualTo(10);
            assertThat(stockOf(socks)).isEqualTo(5);
            assertThat(attemptCount()).isZero();
            assertThat(balanceOf(user)).isEqualTo(USER_BALANCE);
            assertThat(pg.createCalls()).isZero();
        }
    }

    @DisplayName("결과를 반영하는 트랜잭션 B에서,")
    @Nested
    class TransactionB {
        @DisplayName("재고를 돌려주는 중에 실패하면 시도·주문·재고가 모두 트랜잭션 A 직후 상태로 남는다. 재고를 절반만 돌려주지 않는다.")
        @Test
        void keepsStateAfterTransactionA_whenReturningStockFails() {
            // arrange: 확정 거절이라 TX B 가 시도 FAILED, 주문 PAYMENT_FAILED, 재고 반환을 한꺼번에 한다.
            User user = saveUser(USER_BALANCE);
            Product shirt = saveProduct("티셔츠", 3_000, 10);
            Product socks = saveProduct("양말", 2_000, 5);
            Long orderId = orderFacade.create(user.getId(), List.of(
                new OrderItemCommand(shirt.getId(), 1),
                new OrderItemCommand(socks.getId(), 2)
            )).id();
            pg.onCreate(FakePgServer.rejectBeforeCreate());
            // `update products` 는 TX A 의 재고 확보가 품목 수(2)만큼, 이어서 TX B 의 재고 반환이 나간다. 4번째는 둘째 반환이라
            // 첫 반환이 이미 DB 로 전송된 뒤에 실패한다.
            FailingStatementInspector.arm("update products", 4);

            // act & assert
            assertThatThrownBy(() -> orderFacade.confirm(command(user, orderId, "attempt-1")))
                .hasRootCauseInstanceOf(InjectedFailureException.class);

            FailingStatementInspector.disarm();
            assertThat(orderStatusOf(orderId)).isEqualTo("PAYMENT_PENDING");
            assertThat(attemptOf(orderId)).containsEntry("status", "REQUESTED");
            assertThat(stockOf(shirt)).isEqualTo(9);
            assertThat(stockOf(socks)).isEqualTo(3);
            assertThat(pg.createCalls()).isEqualTo(1);
            assertThat(pg.transactionCount()).isZero();
        }

        @DisplayName("반영이 취소돼도 같은 시도는 저장된 채 남아, 같은 키로 다시 요청해도 PG 에 새로 요청하지 않는다.")
        @Test
        void doesNotResend_whenTransactionBWasRolledBack() {
            // arrange
            User user = saveUser(USER_BALANCE);
            Product shirt = saveProduct("티셔츠", 3_000, 10);
            Long orderId = orderFacade.create(user.getId(), List.of(
                new OrderItemCommand(shirt.getId(), 1)
            )).id();
            pg.onCreate(FakePgServer.rejectBeforeCreate());
            FailingStatementInspector.arm("update products", 2);
            assertThatThrownBy(() -> orderFacade.confirm(command(user, orderId, "attempt-1")))
                .hasRootCauseInstanceOf(InjectedFailureException.class);
            FailingStatementInspector.disarm();

            // act
            PaymentAttemptInfo again = orderFacade.confirm(command(user, orderId, "attempt-1"));

            // assert: 저장된 REQUESTED 를 돌려줄 뿐, 발송 기록이 있는 시도를 자동으로 다시 보내지 않는다.
            assertThat(again.attemptStatus().name()).isEqualTo("REQUESTED");
            assertThat(pg.createCalls()).isEqualTo(1);
            assertThat(stockOf(shirt)).isEqualTo(9);
        }
    }

    @DisplayName("PG 에서 확인한 결과를 반영하는 중에,")
    @Nested
    class ApplyVerifiedResult {
        @DisplayName("성공을 확인한 뒤 내부 저장이 실패하면 전부 취소된다. 같은 거래를 다시 조회해 한 번만 확정하고 create 는 추가되지 않는다.")
        @Test
        void rollsBackSuccess_andSettlesOnceByRequerying() {
            // arrange: 3,000 x 1. 결제를 시작해 PENDING 이고 PG 는 SUCCESS 가 되었다.
            User user = saveUser(USER_BALANCE);
            Product shirt = saveProduct("티셔츠", 3_000, 10);
            Long orderId = orderFacade.create(user.getId(), List.of(new OrderItemCommand(shirt.getId(), 1))).id();
            orderFacade.confirm(command(user, orderId, "attempt-1"));
            pg.setStatus(pgKeyOf(orderId), "SUCCESS");
            // 주문 UPDATE 가 DB 로 나간 뒤 시도 UPDATE 에서 실패시킨다.
            FailingStatementInspector.arm("update payment_attempts", 1);

            // act & assert 1
            assertThatThrownBy(() -> paymentResultCheckFacade.checkMyPaymentResult(user.getId(), orderId))
                .hasRootCauseInstanceOf(InjectedFailureException.class);
            List<String> mutations = FailingStatementInspector.mutations();
            assertThat(mutations.get(mutations.size() - 1)).startsWith("update payment_attempts");
            assertThat(mutations.subList(0, mutations.size() - 1)).anyMatch(sql -> sql.startsWith("update orders"));

            FailingStatementInspector.disarm();
            assertThat(orderStatusOf(orderId)).isEqualTo("PAYMENT_PENDING");
            assertThat(attemptOf(orderId)).containsEntry("status", "PENDING");
            assertThat(jdbcTemplate.queryForObject("select paid_amount from orders where id = ?", Long.class, orderId))
                .isNull();
            assertThat(stockOf(shirt)).isEqualTo(9);

            // act & assert 2: 같은 거래를 다시 조회해 반영한다. 새 결제를 만들지 않는다.
            PaymentCheckInfo again = paymentResultCheckFacade.checkMyPaymentResult(user.getId(), orderId);
            assertThat(again.info().orderStatus().name()).isEqualTo("CONFIRMED");
            assertThat(orderStatusOf(orderId)).isEqualTo("CONFIRMED");
            assertThat(pg.createCalls()).isEqualTo(1);
            assertThat(pg.transactionCount()).isEqualTo(1);
            assertThat(pg.lookupByKeyCalls()).isEqualTo(2);
            assertThat(stockOf(shirt)).isEqualTo(9);
            assertThat(balanceOf(user)).isEqualTo(USER_BALANCE);
        }

        @DisplayName("실패를 확인한 뒤 재고를 돌려주는 중에 저장이 실패하면 전부 취소된다. 다시 조회해도 재고는 한 번만 돌아온다.")
        @Test
        void rollsBackFailure_andReturnsStockOnceByRequerying() {
            // arrange: 품목 둘. 결제를 시작해 재고 9, 4 이고 PG 는 FAILED 가 되었다.
            User user = saveUser(USER_BALANCE);
            Product shirt = saveProduct("티셔츠", 3_000, 10);
            Product socks = saveProduct("양말", 2_000, 5);
            Long orderId = orderFacade.create(user.getId(), List.of(
                new OrderItemCommand(shirt.getId(), 1), new OrderItemCommand(socks.getId(), 1)
            )).id();
            orderFacade.confirm(command(user, orderId, "attempt-1"));
            assertThat(stockOf(shirt)).isEqualTo(9);
            assertThat(stockOf(socks)).isEqualTo(4);
            pg.setStatus(pgKeyOf(orderId), "FAILED");
            // 첫 상품의 재고 반환이 DB 로 나간 뒤 둘째 반환에서 실패시킨다.
            FailingStatementInspector.arm("update products", 2);

            // act & assert 1
            assertThatThrownBy(() -> paymentResultCheckFacade.checkMyPaymentResult(user.getId(), orderId))
                .hasRootCauseInstanceOf(InjectedFailureException.class);
            assertThat(FailingStatementInspector.mutations().stream().filter(sql -> sql.startsWith("update products")))
                .hasSize(2);

            FailingStatementInspector.disarm();
            assertThat(orderStatusOf(orderId)).isEqualTo("PAYMENT_PENDING");
            assertThat(attemptOf(orderId)).containsEntry("status", "PENDING");
            assertThat(stockOf(shirt)).as("절반만 돌려주지 않는다").isEqualTo(9);
            assertThat(stockOf(socks)).isEqualTo(4);

            // act & assert 2
            paymentResultCheckFacade.checkMyPaymentResult(user.getId(), orderId);
            assertThat(orderStatusOf(orderId)).isEqualTo("PAYMENT_FAILED");
            assertThat(stockOf(shirt)).isEqualTo(10);
            assertThat(stockOf(socks)).isEqualTo(5);
            assertThat(pg.createCalls()).isEqualTo(1);
        }
    }
}
