package com.loopers.domain.order;

import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.OrderInfo;
import com.loopers.application.order.OrderItemCommand;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.point.PointModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.point.PointJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
class OrderTransactionTest {

    @Autowired UserJpaRepository userJpaRepository;
    @Autowired BrandJpaRepository brandJpaRepository;
    @Autowired ProductJpaRepository productJpaRepository;
    @Autowired PointJpaRepository pointJpaRepository;
    @Autowired OrderJpaRepository orderJpaRepository;

    @Autowired OrderFacade orderFacade;

    // 실제 구현을 쓰되 특정 메서드 호출만 가로채는 spy
    @SpyBean PointJpaRepository pointJpaRepositorySpy;

    @Autowired DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("주문 확정 중 포인트 차감 단계에서 실패하면 재고·주문 상태가 전부 원상태로 돌아온다.")
    @Test
    void rollsBack_whenPointUseFails() {
        // ── 1. 준비: 데이터 저장 (각 save() 호출 시 DB에 commit됨) ──
        UserModel user = userJpaRepository.save(new UserModel("테스터"));
        BrandModel brand = brandJpaRepository.save(new BrandModel("브랜드A"));
        ProductModel product = productJpaRepository.save(
            new ProductModel(brand.getId(), "상품A", 10_000L, 10)
        );

        // 포인트 잔액 10,000원 세팅: PointModel 생성 → charge() → save
        PointModel point = new PointModel(user.getId());
        point.charge(10_000L);
        pointJpaRepository.save(point);

        // DRAFT 주문 생성: 상품 1개, 수량 1개 → totalAmount = 10,000
        OrderInfo orderInfo = orderFacade.createOrder(
            user.getId(),
            List.of(new OrderItemCommand(product.getId(), 1))
        );
        Long orderId = orderInfo.id();

        // ── 2. 포인트 잠금 조회 시 예외 강제 발생 (재고 차감 이후 단계) ──
        // PointService.use() 는 findByUserIdWithLock() 을 호출한다
        doThrow(new RuntimeException("강제 실패"))
            .when(pointJpaRepositorySpy).findByUserIdWithLock(any());

        // ── 3. 주문 확정 시도 → 예외가 밖으로 나와야 함 ──
        assertThatThrownBy(() -> orderFacade.confirmOrder(user.getId(), orderId))
            .isInstanceOf(Exception.class);

        // ── 4. 롤백 확인 — 트랜잭션 종료 후 새 조회로 확인 ──
        // 재고: 차감 전 그대로 10
        ProductModel reloadedProduct = productJpaRepository.findById(product.getId()).get();
        assertThat(reloadedProduct.getStock()).isEqualTo(10);

        // 주문 상태: 여전히 DRAFT
        OrderModel reloadedOrder = orderJpaRepository.findById(orderId).get();
        assertThat(reloadedOrder.getStatus()).isEqualTo(OrderStatus.DRAFT);

        // 포인트 잔액: 변화 없이 10,000
        PointModel reloadedPoint = pointJpaRepository.findByUserId(user.getId()).get();
        assertThat(reloadedPoint.getBalance()).isEqualTo(10_000L);
    }
}
