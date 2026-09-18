package com.loopers.order.application;

import com.loopers.order.domain.Order;
import com.loopers.order.domain.OrderItem;
import com.loopers.order.domain.OrderRepository;
import com.loopers.order.domain.OrderStatus;
import com.loopers.product.domain.Product;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.user.domain.User;
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
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Import(MySqlTestContainersConfig.class)
@Transactional
class OrderUseCaseIntegrationTest {
    @Autowired private OrderUseCase useCase;
    @Autowired private OrderRepository orderRepository;
    @Autowired private EntityManager entityManager;

    @DisplayName("[R-ORDER-04] 주문 생성만으로 재고와 포인트를 차감하지 않는다.")
    @Nested class CreateWithoutDeduction {
        @DisplayName("[상태 전이] 재고 5와 잔액 10000에서 주문 생성 후 저장 값을 유지한다.")
        @Test void keepsStockAndPointOnCreate() {
            Product product = persist(productWithStock(5));
            User buyer = persist(userWithPoint(10_000L));
            useCase.create(buyer.getId(),
                List.of(new OrderUseCase.ItemCommand(product.getId(), 2)));
            entityManager.flush();
            entityManager.clear();
            assertAll(
                () -> assertThat(entityManager.find(Product.class, product.getId()).getStock().quantity())
                    .isEqualTo(5),
                () -> assertThat(entityManager.find(User.class, buyer.getId()).getPoint().balance())
                    .isEqualTo(10_000L)
            );
        }
    }

    @DisplayName("[R-ORDER-05] 존재하지 않거나 삭제된 상품은 주문에 사용할 수 없다.")
    @Nested class RejectUnavailableProduct {
        @DisplayName("[동등 클래스 분할] 존재하지 않는 상품이면 PRODUCT_NOT_FOUND이고 주문은 저장되지 않는다.")
        @Test void rejectsUnknownProduct() {
            User buyer = persist(new User());
            CoreException result = assertThrows(CoreException.class, () -> useCase.create(
                buyer.getId(), List.of(new OrderUseCase.ItemCommand(999L, 1))));
            assertThat(result.getErrorCode()).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);
            assertThat(orderRepository.findAllByBuyerId(buyer.getId(), 0, 20)).isEmpty();
        }

        @DisplayName("[동등 클래스 분할] 삭제된 상품이면 PRODUCT_NOT_FOUND이고 주문은 저장되지 않는다.")
        @Test void rejectsDeletedProduct() {
            Product deleted = productWithStock(5);
            deleted.delete();
            persist(deleted);
            User buyer = persist(new User());
            CoreException result = assertThrows(CoreException.class, () -> useCase.create(
                buyer.getId(), List.of(new OrderUseCase.ItemCommand(deleted.getId(), 1))));
            assertThat(result.getErrorCode()).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);
            assertThat(orderRepository.findAllByBuyerId(buyer.getId(), 0, 20)).isEmpty();
        }
    }

    @DisplayName("[R-ORDER-10] 재고나 포인트가 부족하면 주문 확정을 거절한다.")
    @Nested class KeepAllStateOnRejectedConfirmation {
        @DisplayName("[경계값 분석] 재고가 한 개 부족하면 오류이고 세 저장 값을 유지한다.")
        @Test void keepsPersistentStateOnInsufficientStock() {
            Scenario scenario = confirmationScenario(1, 10_000L);
            CoreException result = assertThrows(CoreException.class, () -> useCase.confirm(
                scenario.buyer().getId(), scenario.order().getId()));
            entityManager.clear();
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK),
                () -> assertThat(entityManager.find(Order.class, scenario.order().getId()).getStatus())
                    .isEqualTo(OrderStatus.DRAFT),
                () -> assertThat(entityManager.find(Product.class, scenario.product().getId())
                    .getStock().quantity()).isEqualTo(1),
                () -> assertThat(entityManager.find(User.class, scenario.buyer().getId())
                    .getPoint().balance()).isEqualTo(10_000L)
            );
        }

        @DisplayName("[경계값 분석] 포인트가 1 부족하면 오류이고 세 저장 값을 유지한다.")
        @Test void keepsPersistentStateOnInsufficientPoint() {
            Scenario scenario = confirmationScenario(5, 3_999L);
            CoreException result = assertThrows(CoreException.class, () -> useCase.confirm(
                scenario.buyer().getId(), scenario.order().getId()));
            entityManager.clear();
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_POINT),
                () -> assertThat(entityManager.find(Order.class, scenario.order().getId()).getStatus())
                    .isEqualTo(OrderStatus.DRAFT),
                () -> assertThat(entityManager.find(Product.class, scenario.product().getId())
                    .getStock().quantity()).isEqualTo(5),
                () -> assertThat(entityManager.find(User.class, scenario.buyer().getId())
                    .getPoint().balance()).isEqualTo(3_999L)
            );
        }
    }

    @DisplayName("[R-ORDER-11] 주문 확정에 성공하면 상품 재고와 고객 포인트를 차감한다.")
    @Nested class PersistConfirmation {
        @DisplayName("[상태 전이] 재고 5와 잔액 10000에서 4000원 주문 확정 후 3과 6000이 저장된다.")
        @Test void savesOrderProductAndBuyerTogether() {
            Scenario scenario = confirmationScenario(5, 10_000L);
            useCase.confirm(scenario.buyer().getId(), scenario.order().getId());
            entityManager.flush();
            entityManager.clear();
            assertAll(
                () -> assertThat(entityManager.find(Product.class, scenario.product().getId())
                    .getStock().quantity()).isEqualTo(3),
                () -> assertThat(entityManager.find(User.class, scenario.buyer().getId())
                    .getPoint().balance()).isEqualTo(6_000L)
            );
        }
    }

    @DisplayName("[R-ORDER-13] 고객은 자신의 주문 목록을 조회할 수 있다.")
    @Nested class FindMine {
        @DisplayName("[동등 클래스 분할] 다른 구매자의 주문을 제외하고 요청 고객의 주문만 조회한다.")
        @Test void findsRequesterOrders() {
            Order mine = persist(orderOf(1L, 10L));
            persist(orderOf(2L, 20L));
            assertThat(useCase.findMine(1L, 0, 20)).extracting(Order::getId)
                .containsExactly(mine.getId());
        }

        @DisplayName("[동등 클래스 분할] 자신의 주문 식별자로 상세를 조회한다.")
        @Test void findsRequesterOrderDetail() {
            Order mine = persist(orderOf(1L, 10L));
            assertThat(useCase.findMine(1L, mine.getId()).getId()).isEqualTo(mine.getId());
        }
    }

    @DisplayName("[R-ADMIN-10] 관리자는 구매자별 주문 목록을 조회할 수 있다.")
    @Nested class FindForAdmin {
        @DisplayName("[동등 클래스 분할] 구매자 조건이 있으면 그 구매자의 주문만 조회한다.")
        @Test void filtersAdminOrdersByBuyer() {
            Order selected = persist(orderOf(1L, 10L));
            persist(orderOf(2L, 20L));
            assertThat(useCase.findForAdmin(1L, 0, 20)).extracting(Order::getId)
                .containsExactly(selected.getId());
        }

        @DisplayName("[동등 클래스 분할] 관리자는 주문 식별자로 상세를 조회한다.")
        @Test void findsOrderDetailForAdmin() {
            Order order = persist(orderOf(1L, 10L));
            assertThat(useCase.findForAdmin(order.getId()).getId()).isEqualTo(order.getId());
        }
    }

    @DisplayName("[P-ADMIN-10] 모든 주문을 구매자와 함께 보여 주고 구매자 필터도 제공한다.")
    @Nested class OptionalBuyerFilter {
        @DisplayName("[의사결정표] 구매자 조건이 없으면 두 구매자의 주문을 모두 조회한다.")
        @Test void findsAllAdminOrdersWithoutBuyerFilter() {
            Order first = persist(orderOf(1L, 10L));
            Order second = persist(orderOf(2L, 20L));
            assertThat(useCase.findForAdmin(null, 0, 20)).extracting(Order::getId)
                .containsExactlyInAnyOrder(first.getId(), second.getId());
        }
    }

    private Scenario confirmationScenario(int stock, long point) {
        Product product = persist(productWithStock(stock));
        User buyer = persist(userWithPoint(point));
        Order order = persist(new Order(buyer.getId(), List.of(OrderItem.of(product, 2))));
        return new Scenario(product, buyer, order);
    }

    private static Product productWithStock(int stock) {
        Product product = new Product(1L, "상품", 2_000L);
        product.changeStock(stock);
        return product;
    }

    private static User userWithPoint(long point) {
        User user = new User();
        if (point > 0) {
            user.charge(point);
        }
        return user;
    }

    private static Order orderOf(Long buyerId, Long productId) {
        return new Order(buyerId, List.of(new OrderItem(productId, "상품", 1, 100L)));
    }

    private <T> T persist(T entity) {
        entityManager.persist(entity);
        entityManager.flush();
        return entity;
    }

    private record Scenario(Product product, User buyer, Order order) {
    }
}
