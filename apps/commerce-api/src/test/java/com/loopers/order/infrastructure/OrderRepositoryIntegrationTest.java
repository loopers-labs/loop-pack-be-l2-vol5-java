package com.loopers.order.infrastructure;

import com.loopers.order.domain.Order;
import com.loopers.order.domain.OrderItem;
import com.loopers.order.domain.OrderRepository;
import com.loopers.product.domain.Product;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(MySqlTestContainersConfig.class)
@Transactional
class OrderRepositoryIntegrationTest {

    @Autowired
    private OrderRepository repository;
    @Autowired
    private EntityManager entityManager;

    @DisplayName("[R-ADMIN-14] 상품을 삭제해도 저장된 주문 품목은 함께 삭제되지 않는다.")
    @Nested
    class KeepOrderSnapshot {
        @DisplayName("[상태 전이] 상품 삭제 후 flush·clear해도 주문 당시 품목 정보가 남는다.")
        @Test
        void keepsItemAfterProductDeletion() {
            Product product = new Product(1L, "주문 상품", 3_000L);
            entityManager.persist(product);
            entityManager.flush();
            Order order = repository.save(new Order(1L, List.of(OrderItem.of(product, 2))));
            product.delete();
            flushAndClear();

            Order result = repository.findById(order.getId()).orElseThrow();

            assertThat(result.getItems()).singleElement().satisfies(item -> {
                assertThat(item.productName()).isEqualTo("주문 상품");
                assertThat(item.unitPrice()).isEqualTo(3_000L);
            });
        }
    }

    @DisplayName("[P-ORDER-07] 주문 품목은 주문 당시의 상품 정보를 조회한다.")
    @Nested
    class ReloadSnapshotValues {
        @DisplayName("[상태 전이] 현재 상품 이름과 가격이 바뀌어도 저장된 품목 값은 바뀌지 않는다.")
        @Test
        void reloadsOriginalProductValues() {
            Product product = new Product(1L, "이전 이름", 3_000L);
            entityManager.persist(product);
            entityManager.flush();
            Order order = repository.save(new Order(1L, List.of(OrderItem.of(product, 1))));
            product.update("새 이름", 3_500L, 1L);
            flushAndClear();

            OrderItem item = repository.findById(order.getId()).orElseThrow().getItems().getFirst();

            assertThat(item.productName()).isEqualTo("이전 이름");
            assertThat(item.unitPrice()).isEqualTo(3_000L);
        }
    }

    @DisplayName("[P-ORDER-09] 고객과 관리자 주문 목록은 생성 최신순으로 페이지 조회한다.")
    @Nested
    class FindLatestPage {
        @DisplayName("[동등 클래스 분할] 첫 페이지 크기 1은 나중에 생성한 주문이다.")
        @Test
        void returnsLatestOrderFirst() {
            Order older = repository.save(orderOf(1L, 10L));
            Order newer = repository.save(orderOf(1L, 20L));
            flushAndClear();

            List<Order> result = repository.findAllByBuyerId(1L, 0, 1);

            assertThat(result).extracting(Order::getId).containsExactly(newer.getId());
            assertThat(result).extracting(Order::getId).doesNotContain(older.getId());
        }
    }

    private static Order orderOf(Long buyerId, Long productId) {
        return new Order(buyerId, List.of(new OrderItem(productId, "상품", 1, 100L)));
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
