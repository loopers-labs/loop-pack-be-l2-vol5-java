package com.loopers.infrastructure.order;

import com.loopers.domain.common.Money;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderLine;
import com.loopers.domain.order.OrderLines;
import com.loopers.domain.order.OrderProduct;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.PaymentResult;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OrderRepositoryTest {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderJpaRepository orderJpaRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    // 4-1 시나리오: 3,000 × 1 + 2,000 × 2 = 7,000
    private Order newOrder(Long userId) {
        return Order.create(
            userId,
            new OrderLines(List.of(new OrderLine(10L, 1), new OrderLine(20L, 2))),
            Map.of(
                10L, new OrderProduct("티셔츠", new Money(3_000)),
                20L, new OrderProduct("양말", new Money(2_000))
            )
        );
    }

    @DisplayName("주문을 저장하고 다시 읽을 때,")
    @Nested
    class SaveAndFind {
        @DisplayName("상태·합계·품목(상품명·수량·단가·금액)이 DB 에서 그대로 읽히고, 시각이 채워진다.")
        @Test
        void returnsSavedOrderWithItems() {
            // arrange
            Long orderId = orderRepository.save(newOrder(7L)).getId();

            // act: 같은 메모리 객체가 아니라 DB 에서 다시 읽는다. 품목은 지연 로딩이라 트랜잭션 안에서 읽는다.
            transactionTemplate.executeWithoutResult(status -> {
                Order found = orderJpaRepository.findById(orderId).orElseThrow();

                // assert
                assertThat(found.getUserId()).isEqualTo(7L);
                assertThat(found.getStatus()).isEqualTo(OrderStatus.DRAFT);
                assertThat(found.getTotalAmount()).isEqualTo(new Money(7_000));
                assertThat(found.getCreatedAt()).isNotNull();
                assertThat(found.getItems()).hasSize(2);

                OrderItem first = found.getItems().get(0);
                assertThat(first.getProductId()).isEqualTo(10L);
                assertThat(first.getProductName()).isEqualTo("티셔츠");
                assertThat(first.getQuantity()).isEqualTo(1);
                assertThat(first.getUnitPrice()).isEqualTo(new Money(3_000));
                assertThat(first.getAmount()).isEqualTo(new Money(3_000));

                OrderItem second = found.getItems().get(1);
                assertThat(second.getProductId()).isEqualTo(20L);
                assertThat(second.getProductName()).isEqualTo("양말");
                assertThat(second.getQuantity()).isEqualTo(2);
                assertThat(second.getUnitPrice()).isEqualTo(new Money(2_000));
                assertThat(second.getAmount()).isEqualTo(new Money(4_000));
            });
        }

        @DisplayName("품목은 주문과 함께 저장되어 order_items 에 주문 id 로 연결된다.")
        @Test
        void savesItemsTogetherWithOrder() {
            // act
            Long orderId = orderRepository.save(newOrder(7L)).getId();

            // assert
            Integer count = jdbcTemplate.queryForObject(
                "select count(*) from order_items where order_id = ?", Integer.class, orderId
            );
            assertThat(count).isEqualTo(2);
        }
    }

    @DisplayName("결제 결과를 저장하고 다시 읽을 때,")
    @Nested
    class PaymentResultMapping {
        @DisplayName("확정한 주문은 CONFIRMED 와 결제액이 DB 에서 그대로 읽힌다.")
        @Test
        void returnsPaymentResult_whenOrderIsConfirmed() {
            // arrange
            Order order = newOrder(7L);
            order.confirm(new PaymentResult(new Money(7_000)));
            Long orderId = orderRepository.save(order).getId();

            // act & assert
            transactionTemplate.executeWithoutResult(status -> {
                Order found = orderJpaRepository.findById(orderId).orElseThrow();
                assertThat(found.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
                assertThat(found.getPaymentResult())
                    .isEqualTo(new PaymentResult(new Money(7_000)));
            });
        }

        @DisplayName("결제액이 0원이어도 결제 결과가 있는 것으로 읽힌다 (0원과 없음을 구분한다).")
        @Test
        void returnsPaymentResult_whenPaidAmountIsZero() {
            // arrange: 결제 결과의 컬럼이 하나뿐이라, 0원이 컬럼 null(결제 결과 없음)과 섞이지 않는지 매핑만 확인한다.
            Order order = newOrder(7L);
            order.confirm(new PaymentResult(new Money(0)));
            Long orderId = orderRepository.save(order).getId();

            // act & assert
            transactionTemplate.executeWithoutResult(status -> {
                Order found = orderJpaRepository.findById(orderId).orElseThrow();
                assertThat(found.getPaymentResult()).isNotNull();
                assertThat(found.getPaymentResult().paidAmount()).isEqualTo(new Money(0));
            });
        }

        @DisplayName("PG 결제로 확정한 주문은 결제액이 읽히고, 결제 대기·실패 상태는 결제 결과가 null 이다.")
        @Test
        void returnsPaymentResult_andPaymentStatuses() {
            // arrange
            Order paid = newOrder(7L);
            paid.markPaymentPending();
            paid.completePayment(new PaymentResult(new Money(7_000)));
            Order pending = newOrder(7L);
            pending.markPaymentPending();
            Order failed = newOrder(7L);
            failed.markPaymentPending();
            failed.failPayment();
            Long paidId = orderRepository.save(paid).getId();
            Long pendingId = orderRepository.save(pending).getId();
            Long failedId = orderRepository.save(failed).getId();

            // act & assert
            transactionTemplate.executeWithoutResult(status -> {
                Order foundPaid = orderJpaRepository.findById(paidId).orElseThrow();
                assertThat(foundPaid.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
                assertThat(foundPaid.getPaymentResult())
                    .isEqualTo(new PaymentResult(new Money(7_000)));

                Order foundPending = orderJpaRepository.findById(pendingId).orElseThrow();
                assertThat(foundPending.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
                assertThat(foundPending.getPaymentResult()).isNull();

                Order foundFailed = orderJpaRepository.findById(failedId).orElseThrow();
                assertThat(foundFailed.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
                assertThat(foundFailed.getPaymentResult()).isNull();
            });
        }
    }

    @DisplayName("본인 주문을 조회할 때,")
    @Nested
    class FindByIdAndUserId {
        @DisplayName("주문자가 같으면 조회된다.")
        @Test
        void returnsOrder_whenUserIsOwner() {
            // arrange
            Long orderId = orderRepository.save(newOrder(7L)).getId();

            // act
            Optional<Order> found = orderRepository.findByIdAndUserId(orderId, 7L);

            // assert
            assertThat(found).isPresent();
            assertThat(found.get().getId()).isEqualTo(orderId);
        }

        @DisplayName("다른 사용자의 주문이나 없는 주문은 빈 결과다.")
        @Test
        void returnsEmpty_whenOrderBelongsToOtherUserOrDoesNotExist() {
            // arrange
            Long orderId = orderRepository.save(newOrder(7L)).getId();

            // act & assert
            assertThat(orderRepository.findByIdAndUserId(orderId, 8L)).isEmpty();
            assertThat(orderRepository.findByIdAndUserId(999_999L, 7L)).isEmpty();
        }
    }

    @DisplayName("주문 목록을 조회할 때,")
    @Nested
    class FindAll {
        @DisplayName("구매자를 지정하면 그 구매자의 주문만 최신순(id 내림차순)으로 조회된다.")
        @Test
        void returnsOnlyThatUsersOrders_newestFirst() {
            // arrange
            Long first = orderRepository.save(newOrder(7L)).getId();
            orderRepository.save(newOrder(8L));
            Long second = orderRepository.save(newOrder(7L)).getId();

            // act
            Page<Order> page = orderRepository.findAll(7L, PageRequest.of(0, 10));

            // assert
            assertThat(page.getContent()).extracting(Order::getId).containsExactly(second, first);
            assertThat(page.getTotalElements()).isEqualTo(2);
        }

        @DisplayName("구매자를 지정하지 않으면 모든 주문이 최신순으로 조회된다.")
        @Test
        void returnsAllOrders_whenUserIsNull() {
            // arrange
            Long first = orderRepository.save(newOrder(7L)).getId();
            Long second = orderRepository.save(newOrder(8L)).getId();

            // act
            Page<Order> page = orderRepository.findAll(null, PageRequest.of(0, 10));

            // assert
            assertThat(page.getContent()).extracting(Order::getId).containsExactly(second, first);
        }

        @DisplayName("페이지 크기만큼 나누어 조회하고 총 개수와 전체 페이지 수가 정확하다.")
        @Test
        void paginates() {
            // arrange
            for (int i = 0; i < 3; i++) {
                orderRepository.save(newOrder(7L));
            }

            // act
            Page<Order> firstPage = orderRepository.findAll(7L, PageRequest.of(0, 2));
            Page<Order> secondPage = orderRepository.findAll(7L, PageRequest.of(1, 2));

            // assert
            assertThat(firstPage.getContent()).hasSize(2);
            assertThat(secondPage.getContent()).hasSize(1);
            assertThat(firstPage.getTotalElements()).isEqualTo(3);
            assertThat(firstPage.getTotalPages()).isEqualTo(2);
        }

        @DisplayName("호출하는 쪽이 Pageable 에 정렬을 실어도 최신순으로 고정된다.")
        @Test
        void ignoresSortOfPageable() {
            // arrange
            Long first = orderRepository.save(newOrder(7L)).getId();
            Long second = orderRepository.save(newOrder(7L)).getId();

            // act
            Page<Order> page = orderRepository.findAll(
                7L, PageRequest.of(0, 10, org.springframework.data.domain.Sort.by("id").ascending())
            );

            // assert
            assertThat(page.getContent()).extracting(Order::getId).containsExactly(second, first);
        }
    }

}
