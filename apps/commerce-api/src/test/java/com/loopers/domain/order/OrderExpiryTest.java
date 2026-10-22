package com.loopers.domain.order;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class OrderExpiryTest {

    @Autowired UserJpaRepository userJpaRepository;
    @Autowired BrandJpaRepository brandJpaRepository;
    @Autowired ProductJpaRepository productJpaRepository;
    @Autowired OrderJpaRepository orderJpaRepository;
    @Autowired OrderService orderService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("만료 시각이 지난 DRAFT 주문은 스케줄러 실행 시 EXPIRED로 바뀐다.")
    @Test
    void expiresDraftOrder_whenExpiredAtHasPassed() {
        UserModel user = userJpaRepository.save(new UserModel("테스터"));
        BrandModel brand = brandJpaRepository.save(new BrandModel("브랜드A"));
        ProductModel product = productJpaRepository.save(
            new ProductModel(brand.getId(), "상품A", 10_000L, 10)
        );

        OrderModel order = orderJpaRepository.save(
            new OrderModel(user.getId(), List.of(new OrderItemModel(product.getId(), 1, 10_000L)))
        );

        // expired_at을 과거로 강제 설정 (실제 10분을 기다리지 않기 위해)
        jdbcTemplate.update(
            "UPDATE orders SET expired_at = NOW() - INTERVAL 1 MINUTE WHERE id = ?",
            order.getId()
        );

        orderService.expireDraftOrders();

        OrderModel result = orderJpaRepository.findById(order.getId()).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(OrderStatus.EXPIRED);
    }

    @DisplayName("만료되지 않은 DRAFT 주문은 스케줄러 실행 후에도 상태가 유지된다.")
    @Test
    void keepsDraftOrder_whenNotYetExpired() {
        UserModel user = userJpaRepository.save(new UserModel("테스터"));
        BrandModel brand = brandJpaRepository.save(new BrandModel("브랜드A"));
        ProductModel product = productJpaRepository.save(
            new ProductModel(brand.getId(), "상품A", 10_000L, 10)
        );

        orderJpaRepository.save(
            new OrderModel(user.getId(), List.of(new OrderItemModel(product.getId(), 1, 10_000L)))
        );

        orderService.expireDraftOrders();

        List<OrderModel> drafts = orderJpaRepository.findAllByStatusAndExpiredAtBefore(
            OrderStatus.DRAFT, java.time.ZonedDateTime.now().plusHours(1)
        );
        assertThat(drafts).hasSize(1);
    }

    @DisplayName("만료 시각이 지난 DRAFT 주문은 확정할 수 없다.")
    @Test
    void rejectsConfirm_whenOrderIsExpired() {
        UserModel user = userJpaRepository.save(new UserModel("테스터"));
        BrandModel brand = brandJpaRepository.save(new BrandModel("브랜드A"));
        ProductModel product = productJpaRepository.save(
            new ProductModel(brand.getId(), "상품A", 10_000L, 10)
        );

        OrderModel order = orderJpaRepository.save(
            new OrderModel(user.getId(), List.of(new OrderItemModel(product.getId(), 1, 10_000L)))
        );

        jdbcTemplate.update(
            "UPDATE orders SET expired_at = NOW() - INTERVAL 1 MINUTE WHERE id = ?",
            order.getId()
        );

        OrderModel reloaded = orderJpaRepository.findById(order.getId()).orElseThrow();
        assertThatThrownBy(reloaded::requireDraft)
            .isInstanceOf(CoreException.class)
            .hasMessageContaining("만료된 주문");
    }
}
