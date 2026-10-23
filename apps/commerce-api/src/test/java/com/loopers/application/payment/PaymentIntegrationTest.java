package com.loopers.application.payment;

import com.loopers.application.order.OrderAdminInfo;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.PaymentMethod;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.ReservationStatus;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PgCreateResult;
import com.loopers.domain.payment.UnknownReason;
import com.loopers.support.pg.PaymentTestSupport;
import com.loopers.support.pg.TestPgStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 주문 확정의 PG 결제 시나리오 (과제 6절, design.md 9-9절). 실제 MySQL·controller·adapter와 테스트 PG fixture를 거친다.
 * 각 장면은 외부 거래 수, create 호출 수, 주문·예약 상태, 재고, 포인트를 함께 대조한다.
 * callback·reconcile의 순서·동시성은 {@link PaymentCallbackIntegrationTest}, 내부 실패·재시작·회로는 {@link PaymentRecoveryIntegrationTest}가 맡는다.
 */
class PaymentIntegrationTest extends PaymentScenarioTestBase {

    @DisplayName("정상 결제와 접수만 된 결제에서, ")
    @Nested
    class AcceptedPayment {

        @DisplayName("정상 PG 주문은 외부 거래 1, 주문 CONFIRMED, 예약 소비 1이고 포인트는 변하지 않는다.")
        @Test
        void confirmsOrder_whenPgSucceeds() throws Exception {
            // act
            confirm().andExpect(status().isAccepted());
            pgCompletes(TestPgStatus.SUCCESS);
            reconcile().andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.paidAmount").value(ORDER_AMOUNT));

            // assert
            assertThat(pg.transactionCount()).as("외부 거래").isEqualTo(1);
            assertThat(pg.createCount()).as("발송").isEqualTo(1);
            assertThat(order().status()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(order().paymentMethod()).isEqualTo(PaymentMethod.PG);
            assertThat(attempt().getStatus()).isEqualTo(PaymentAttemptStatus.SUCCESS);
            assertThat(reservation()).isEqualTo(ReservationStatus.CONSUMED);
            assertThat(stock()).as("확보한 수량 그대로, 추가 차감 없음").isEqualTo(INITIAL_STOCK - ORDER_QUANTITY);
            assertThat(balance()).as("포인트 변화 0").isEqualTo(INITIAL_BALANCE);
        }

        @DisplayName("생성 수락(PENDING)만으로는 주문을 확정하지 않는다. 확보한 재고를 유지하고, 조회에서 SUCCESS를 확인한 뒤에만 확정한다.")
        @Test
        void keepsOrderPending_untilLookupConfirmsSuccess() throws Exception {
            // act: PG가 아직 처리 중일 때
            confirm().andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("PAYMENT_PENDING"))
                .andExpect(jsonPath("$.data.orderPath").value("/api/v1/orders/" + orderId))
                .andExpect(jsonPath("$.data.reconcilePath").value("/api/v1/orders/" + orderId + "/payment/reconcile"));
            reconcile().andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("PAYMENT_PENDING"))
                .andExpect(jsonPath("$.data.orderPath").value("/api/v1/orders/" + orderId));

            // assert: 접수와 처리 중 확인 모두 확정이 아니다
            assertThat(order().status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(order().paidAmount()).isNull();
            assertThat(attempt().getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
            assertThat(reservation()).isEqualTo(ReservationStatus.RESERVED);
            assertThat(stock()).isEqualTo(INITIAL_STOCK - ORDER_QUANTITY);

            // act: PG가 성공으로 끝낸 뒤
            pgCompletes(TestPgStatus.SUCCESS);
            reconcile().andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.orderPath").doesNotExist())
                .andExpect(jsonPath("$.data.reconcilePath").doesNotExist());

            // assert
            assertThat(order().status()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(pg.createCount()).isEqualTo(1);
        }

        @DisplayName("PG가 확정 실패를 알리면 주문은 PAYMENT_FAILED가 되고 확보한 재고가 한 번 돌아온다. 같은 확인이 다시 와도 더 돌아오지 않는다.")
        @Test
        void returnsReservedStockOnce_whenPgFails() throws Exception {
            // act
            confirm().andExpect(status().isAccepted());
            pgCompletes(TestPgStatus.FAILED);
            reconcile().andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PAYMENT_FAILED"));
            reconcile().andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PAYMENT_FAILED"));

            // assert
            assertThat(order().status()).isEqualTo(OrderStatus.PAYMENT_FAILED);
            assertThat(order().paidAmount()).isNull();
            assertThat(attempt().getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
            assertThat(reservation()).isEqualTo(ReservationStatus.RELEASED);
            assertThat(stock()).as("확보 전 수량으로 정확히 한 번 복구").isEqualTo(INITIAL_STOCK);
            assertThat(balance()).isEqualTo(INITIAL_BALANCE);
        }
    }

    @DisplayName("효과 전 확정 거절에서, ")
    @Nested
    class RejectedBeforeCreate {

        @DisplayName("외부 거래 0, 주문 실패, 예약 반환 1이다. 거래가 없으므로 거래 키를 꾸며 저장하지 않는다.")
        @Test
        void failsOrder_andReturnsReservationOnce() throws Exception {
            // arrange
            pg.rejectNextCreate();

            // act
            confirm().andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PAYMENT_FAILED"));

            // assert
            assertThat(pg.transactionCount()).as("외부 거래").isZero();
            assertThat(pg.createCount()).isEqualTo(1);
            assertThat(order().status()).isEqualTo(OrderStatus.PAYMENT_FAILED);
            assertThat(attempt().getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
            assertThat(attempt().getTransactionKey()).isNull();
            assertThat(reservation()).isEqualTo(ReservationStatus.RELEASED);
            assertThat(stock()).isEqualTo(INITIAL_STOCK);
            assertThat(balance()).isEqualTo(INITIAL_BALANCE);
        }

        @DisplayName("같은 거절을 다시 적용하거나 같은 요청을 반복해도 반환이 늘지 않고, PG를 다시 부르지 않는다.")
        @Test
        void doesNotReturnTwice_whenSameRejectionIsAppliedAgain() throws Exception {
            // arrange
            pg.rejectNextCreate();
            confirm().andExpect(status().isOk());

            // act
            paymentTransactions.applyCreate(orderId, new PgCreateResult.RejectedNoEffect());
            paymentTransactions.applyCreate(orderId, new PgCreateResult.RejectedNoEffect());
            confirm().andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PAYMENT_FAILED"));
            reconcile().andExpect(status().isOk());

            // assert: 7이나 9가 아니라 5다
            assertThat(stock()).isEqualTo(INITIAL_STOCK);
            assertThat(reservation()).isEqualTo(ReservationStatus.RELEASED);
            assertThat(pg.createCount()).isEqualTo(1);
            assertThat(paymentAttemptJpaRepository.count()).isEqualTo(1);
        }

        @DisplayName("거절이 아닌 오류(500)로 응답받으면 실패로 정하지 않는다. 거래가 생겼는지 모르므로 결과 미확인이고 예약을 유지한다.")
        @Test
        void treatsGenericServerErrorAsUnknown_notAsRejection() throws Exception {
            // arrange: PG는 거래를 만들었지만 응답은 500이다
            pg.respondNextCreate(500, "{\"code\":\"BOOM\"}", true);

            // act
            confirm().andExpect(status().isAccepted()).andExpect(jsonPath("$.data.status").value("PAYMENT_PENDING"));

            // assert
            assertThat(attempt().getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
            assertThat(attempt().getUnknownReason()).isEqualTo(UnknownReason.UNEXPECTED_STATUS);
            assertThat(reservation()).isEqualTo(ReservationStatus.RESERVED);
            assertThat(stock()).isEqualTo(INITIAL_STOCK - ORDER_QUANTITY);
            assertThat(pg.transactionCount()).isEqualTo(1);
        }
    }

    @DisplayName("거래 생성 뒤 응답만 유실되면, ")
    @Nested
    class LostResponse {

        @DisplayName("연결이 끊겨도 거래 1·결과 미확인·예약 유지이고 create는 더 보내지 않는다. 같은 거래를 조회해 한 번 종결한다.")
        @Test
        void staysUnknown_thenSettlesByLookup_withoutResending() throws Exception {
            // arrange
            pg.dropNextCreateResponse();

            // act: 응답을 잃은 확정
            confirm().andExpect(status().isAccepted()).andExpect(jsonPath("$.data.status").value("PAYMENT_PENDING"));

            // assert: 실패가 아니라 결과 미확인이다
            assertThat(pg.transactionCount()).as("외부 거래").isEqualTo(1);
            assertThat(pg.createCount()).isEqualTo(1);
            assertThat(attempt().getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
            assertThat(attempt().getUnknownReason()).isEqualTo(UnknownReason.CONNECTION_LOST);
            assertThat(attempt().getTransactionKey()).as("응답을 잃어 아직 거래 키를 모른다").isNull();
            assertThat(reservation()).isEqualTo(ReservationStatus.RESERVED);
            assertThat(stock()).isEqualTo(INITIAL_STOCK - ORDER_QUANTITY);

            // act: PG가 성공으로 끝낸 뒤 주문 번호로 조회해 종결
            pgCompletes(TestPgStatus.SUCCESS);
            reconcile().andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CONFIRMED"));

            // assert
            assertThat(attempt().getTransactionKey()).isEqualTo(pgTransaction().transactionKey());
            assertThat(reservation()).isEqualTo(ReservationStatus.CONSUMED);
            assertThat(stock()).isEqualTo(INITIAL_STOCK - ORDER_QUANTITY);
            assertThat(pg.createCount()).as("create 추가 0").isEqualTo(1);
            assertThat(balance()).isEqualTo(INITIAL_BALANCE);
        }

        @DisplayName("응답이 늦어 timeout돼도(TIMEOUT) 실패로 정하지 않는다. 예약을 유지하고 create를 다시 보내지 않는다.")
        @Test
        void staysUnknown_whenResponseTimesOut() throws Exception {
            // arrange
            pg.holdNextCreateResponse();

            // act
            confirm().andExpect(status().isAccepted());

            // assert
            assertThat(attempt().getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
            assertThat(attempt().getUnknownReason()).isEqualTo(UnknownReason.TIMEOUT);
            assertThat(reservation()).isEqualTo(ReservationStatus.RESERVED);
            assertThat(stock()).isEqualTo(INITIAL_STOCK - ORDER_QUANTITY);
            assertThat(pg.createCount()).isEqualTo(1);
            assertThat(pg.transactionCount()).isEqualTo(1);
        }

        @DisplayName("응답을 잃은 뒤 PG가 확정 실패로 끝내면, 조회로 확인해 예약을 한 번 반환한다.")
        @Test
        void returnsReservation_whenLookupFindsFailure() throws Exception {
            // arrange
            pg.dropNextCreateResponse();
            confirm().andExpect(status().isAccepted());
            pgCompletes(TestPgStatus.FAILED);

            // act
            reconcile().andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PAYMENT_FAILED"));

            // assert
            assertThat(reservation()).isEqualTo(ReservationStatus.RELEASED);
            assertThat(stock()).isEqualTo(INITIAL_STOCK);
            assertThat(pg.createCount()).isEqualTo(1);
        }
    }

    @DisplayName("충돌하는 요청은, ")
    @Nested
    class ConflictingRequests {

        @DisplayName("다른 키·다른 카드 종류·다른 주문의 같은 키 모두 409로 거절되고, 시도·예약·재고·PG에 아무 효과도 없다.")
        @Test
        void isRejectedWithoutEffect() throws Exception {
            // arrange: 첫 요청이 시도를 만든다
            confirm().andExpect(status().isAccepted());
            Long otherOrderId = saveDraft(USER_ID);

            // act & assert
            confirm(orderId, USER_ID, "other-key", PaymentTestSupport.CARD_TYPE).andExpect(status().isConflict());
            confirm(orderId, USER_ID, PaymentTestSupport.attemptKeyOf(orderId), "OTHER").andExpect(status().isConflict());
            confirm(otherOrderId, USER_ID, PaymentTestSupport.attemptKeyOf(orderId), PaymentTestSupport.CARD_TYPE)
                .andExpect(status().isConflict());

            assertThat(paymentAttemptJpaRepository.count()).as("시도").isEqualTo(1);
            assertThat(stockReservationJpaRepository.count()).as("예약").isEqualTo(1);
            assertThat(pg.createCount()).as("발송").isEqualTo(1);
            assertThat(stock()).as("재고는 한 번만 확보").isEqualTo(INITIAL_STOCK - ORDER_QUANTITY);
            assertThat(orderFacade.getOrderForAdmin(otherOrderId).status()).isEqualTo(OrderStatus.DRAFT);
        }
    }

    @DisplayName("PG 호출을 기다리는 동안, ")
    @Nested
    class DuringPgCall {

        @DisplayName("DB 트랜잭션과 재고 잠금을 잡고 있지 않아, 같은 상품의 관리자 변경과 다른 주문의 확정이 PG 응답을 기다리지 않고 끝난다.")
        @Test
        void holdsNoLocks_whileWaitingForPgResponse() throws Exception {
            // arrange: 첫 주문의 PG 응답을 붙잡아 호출이 끝나지 않게 한다. 같은 상품을 담은 다른 주문을 하나 더 만든다
            Long secondOrderId = saveDraft(USER_ID);
            pg.holdNextCreateResponse();
            AtomicReference<Throwable> firstFailure = new AtomicReference<>();
            Thread first = new Thread(() -> {
                try {
                    PaymentTestSupport.startPayment(paymentFacade, orderId, USER_ID);
                } catch (Throwable e) {
                    firstFailure.set(e);
                }
            }, "first-payment");
            first.start();
            await().atMost(Duration.ofSeconds(5)).until(() -> pg.transactionCount() == 1);

            try {
                // act: 첫 호출이 PG 응답을 기다리는 동안, 상품 행 잠금이 필요한 두 작업을 한다
                long startedAt = System.nanoTime();
                productFacade.changeStock(product.getId(), 10);
                confirm(secondOrderId, USER_ID, PaymentTestSupport.attemptKeyOf(secondOrderId), PaymentTestSupport.CARD_TYPE)
                    .andExpect(status().isAccepted());
                Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

                // assert: 첫 호출은 아직 기다리는 중인데, 두 작업은 그 응답(create 읽기 대기 3초)을 기다리지 않고 끝났다
                assertThat(first.isAlive()).as("첫 호출은 아직 PG 응답을 기다린다").isTrue();
                assertThat(elapsed).as("잠금을 잡고 있었다면 첫 호출이 끝날 때까지 기다린다").isLessThan(Duration.ofMillis(1500));
                assertThat(pg.createCount()).as("둘째 주문의 create가 첫 응답을 기다리지 않고 PG에 닿았다").isEqualTo(2);
                assertThat(stock()).as("관리자 설정(10) 뒤 둘째 주문이 2개 확보").isEqualTo(10 - ORDER_QUANTITY);
            } finally {
                pg.releaseHeld();
                first.join(Duration.ofSeconds(10).toMillis());
            }
            assertThat(firstFailure.get()).isNull();
        }
    }

    @DisplayName("기존 기능과 함께, ")
    @Nested
    class ExistingFeatures {

        @DisplayName("이전 POINT 확정 주문은 결제수단·결제액·품목을 그대로 보여 주고, 다른 사용자는 볼 수 없다.")
        @Test
        void keepsPastPointOrder() throws Exception {
            // arrange: 4주차까지의 포인트 결제 사실
            OrderModel past = new OrderModel(USER_ID, List.of(new OrderItem(product.getId(), 1, UNIT_PRICE)));
            past.confirm(UNIT_PRICE);
            Long pastOrderId = orderJpaRepository.save(past).getId();

            // act & assert
            mvc.perform(get("/api/v1/orders/" + pastOrderId).header("X-USER-ID", USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.paymentMethod").value("POINT"))
                .andExpect(jsonPath("$.data.paidAmount").value(UNIT_PRICE))
                .andExpect(jsonPath("$.data.items[0].quantity").value(1))
                .andExpect(jsonPath("$.data.payment").doesNotExist());
            mvc.perform(get("/api/v1/orders/" + pastOrderId).header("X-USER-ID", OTHER_USER_ID))
                .andExpect(status().isNotFound());
        }

        @DisplayName("결제수단 컬럼이 비어 있는(NULL) 이전 확정 주문도, 고객·관리자 조회에서 POINT로 보이고 DB 값은 바뀌지 않는다.")
        @Test
        void showsPoint_whenLegacyConfirmedOrderHasNullPaymentMethod() throws Exception {
            // arrange: 결제수단 컬럼이 생기기 전에 확정한 행을 흉내 낸다 (DB 값을 직접 NULL로 만든다)
            OrderModel past = new OrderModel(USER_ID, List.of(new OrderItem(product.getId(), 1, UNIT_PRICE)));
            past.confirm(UNIT_PRICE);
            Long pastOrderId = orderJpaRepository.save(past).getId();
            jdbcTemplate.update("update orders set payment_method = null where id = ?", pastOrderId);
            assertThat(storedPaymentMethod(pastOrderId)).as("DB에는 값이 비어 있다").isNull();

            // act & assert
            mvc.perform(get("/api/v1/orders/" + pastOrderId).header("X-USER-ID", USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentMethod").value("POINT"))
                .andExpect(jsonPath("$.data.paidAmount").value(UNIT_PRICE))
                .andExpect(jsonPath("$.data.payment").doesNotExist());
            mvc.perform(get("/api-admin/v1/orders/" + pastOrderId).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentMethod").value("POINT"));
            assertThat(storedPaymentMethod(pastOrderId)).as("읽을 때만 보정하고 저장된 값은 바꾸지 않는다").isNull();
        }

        private String storedPaymentMethod(Long targetOrderId) {
            return jdbcTemplate.queryForObject("select payment_method from orders where id = ?", String.class, targetOrderId);
        }

        @DisplayName("재고를 확보한 뒤 상품이 삭제돼도, 저장된 품목으로 성공을 마무리하고 확정 실패 반환은 삭제 상품을 다시 노출하지 않는다.")
        @Test
        void settlesFromStoredItems_andDoesNotReExposeDeletedProduct() throws Exception {
            // arrange
            confirm().andExpect(status().isAccepted());
            mvc.perform(delete("/api-admin/v1/products/" + product.getId()).with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isOk());

            // act: 삭제된 뒤에도 PG 결과를 마무리한다
            pgCompletes(TestPgStatus.FAILED);
            reconcile().andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PAYMENT_FAILED"));

            // assert: 재고는 돌아왔지만 상품은 여전히 삭제된 상태다
            assertThat(stock()).isEqualTo(INITIAL_STOCK);
            assertThat(productJpaRepository.findById(product.getId()).orElseThrow().getDeletedAt()).isNotNull();
            mvc.perform(get("/api/v1/products/" + product.getId())).andExpect(status().isNotFound());
        }

        @DisplayName("재고를 확보한 뒤 상품이 삭제돼도, PG 성공은 저장된 품목·금액으로 CONFIRMED가 된다.")
        @Test
        void confirmsFromStoredItems_whenProductIsDeletedAfterReservation() throws Exception {
            // arrange
            confirm().andExpect(status().isAccepted());
            mvc.perform(delete("/api-admin/v1/products/" + product.getId()).with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isOk());

            // act
            pgCompletes(TestPgStatus.SUCCESS);
            reconcile().andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.paidAmount").value(ORDER_AMOUNT));

            // assert
            assertThat(stock()).isEqualTo(INITIAL_STOCK - ORDER_QUANTITY);
            assertThat(reservation()).isEqualTo(ReservationStatus.CONSUMED);
        }

        @DisplayName("관리자의 재고 설정은 예약을 제외한 가용 수량을 최종값으로 정한다. 예약을 지우지 않고, 확정 실패의 반환은 그 위에 더해진다.")
        @Test
        void keepsReservation_whenAdminSetsAvailableStock() throws Exception {
            // arrange: 재고 5 → 2개 확보 → 가용 3
            confirm().andExpect(status().isAccepted());
            assertThat(stock()).isEqualTo(3);

            // act: 가용 수량을 10으로 설정한 뒤 확정 실패
            productFacade.changeStock(product.getId(), 10);
            assertThat(stock()).as("설정값 그대로").isEqualTo(10);
            pgCompletes(TestPgStatus.FAILED);
            reconcile().andExpect(status().isOk());

            // assert: 확보했던 2개가 가용 수량에 더해진다
            assertThat(stock()).isEqualTo(12);
            assertThat(reservation()).isEqualTo(ReservationStatus.RELEASED);
        }

        @DisplayName("고객 주문 목록과 관리자 주문 조회에 결제수단과 결제 상태가 보이고, 관리자에게만 거래 키가 보인다.")
        @Test
        void showsPaymentState_inListsAndAdminView() throws Exception {
            // arrange
            confirm().andExpect(status().isAccepted());

            // act & assert
            mvc.perform(get("/api/v1/orders").header("X-USER-ID", USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].paymentMethod").value("PG"))
                .andExpect(jsonPath("$.data[0].payment.status").value("PENDING"))
                .andExpect(jsonPath("$.data[0].payment.transactionKey").doesNotExist());
            mvc.perform(get("/api-admin/v1/orders/" + orderId).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentMethod").value("PG"))
                .andExpect(jsonPath("$.data.payment.status").value("PENDING"))
                .andExpect(jsonPath("$.data.payment.transactionKey").value(pgTransaction().transactionKey()));
            OrderAdminInfo admin = order();
            assertThat(admin.payment().conflict()).isNull();
        }
    }
}
