package com.loopers.application.order.facade;

import com.loopers.application.order.dto.OrderDetailInfo;
import com.loopers.application.order.dto.OrderInfo;
import com.loopers.application.order.facade.OrderFacade.OrderRequestLine;
import com.loopers.domain.brand.entity.Brand;
import com.loopers.domain.brand.repository.FakeBrandRepository;
import com.loopers.domain.order.repository.FakeOrderRepository;
import com.loopers.domain.order.entity.Order;
import com.loopers.domain.order.service.OrderService;
import com.loopers.domain.order.enums.OrderStatus;
import com.loopers.domain.payment.repository.FakePaymentRepository;
import com.loopers.domain.payment.service.PaymentService;
import com.loopers.domain.point.repository.FakePointRepository;
import com.loopers.domain.point.service.PointService;
import com.loopers.domain.product.repository.FakeProductLikeCountRepository;
import com.loopers.domain.product.repository.FakeProductRepository;
import com.loopers.domain.product.entity.Product;
import com.loopers.domain.product.enums.ProductErrorCode;
import com.loopers.domain.product.vo.ProductErrorDetail;
import com.loopers.domain.product.service.ProductService;
import com.loopers.support.error.CoreException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 주문 생성 · 조회를 fake 저장 구현으로 DB 없이 확인함. 주문 확정은 OrderPaymentFacadeTest 가 확인함 */
class OrderFacadeTest {

    private static final Long USER_ID = 1L;
    private static final ZonedDateTime CREATED_AT = ZonedDateTime.parse("2026-10-06T10:00:00+09:00[Asia/Seoul]");

    private FakeProductRepository productRepository;
    private FakeOrderRepository orderRepository;
    private PointService pointService;
    private OrderFacade orderFacade;
    private Brand brand;

    @BeforeEach
    void setUp() {
        FakeBrandRepository brandRepository = new FakeBrandRepository();
        productRepository = new FakeProductRepository();
        orderRepository = new FakeOrderRepository();
        pointService = new PointService(new FakePointRepository());
        Clock clock = Clock.fixed(CREATED_AT.toInstant(), CREATED_AT.getZone());
        ProductService productService = new ProductService(productRepository, new FakeProductLikeCountRepository(), clock);
        orderFacade = new OrderFacade(new OrderService(orderRepository, clock, event -> { }), productService, new PaymentService(new FakePaymentRepository(), clock));
        brand = brandRepository.save(new Brand("브랜드", null));
    }

    private Product saveProduct(long price, int stock) {
        Product product = new Product(brand, "상품", price);
        product.changeStock(stock);
        return productRepository.save(product);
    }

    private Order createOrder(OrderRequestLine... lines) {
        return orderOf(orderFacade.createOrder(USER_ID, List.of(lines)).id());
    }

    private Order orderOf(Long orderId) {
        return orderRepository.findById(orderId).orElseThrow();
    }

    private static OrderRequestLine line(Product product, int quantity) {
        return new OrderRequestLine(product.getId(), quantity);
    }

    private static Long failedProductId(CoreException exception) {
        return ((ProductErrorDetail) exception.getDetail()).productId();
    }

    @DisplayName("주문을 만들 때, ")
    @Nested
    class Create {
        @DisplayName("상품명 · 단가를 스냅샷으로 남긴 DRAFT 를 저장하고, 재고와 포인트는 차감하지 않는다.")
        @Test
        void createsDraftWithoutDeduction() {
            // arrange
            Product product = saveProduct(1_000L, 10);
            pointService.charge(USER_ID, 10_000L);

            // act
            Order order = createOrder(line(product, 3));

            // assert
            assertAll(
                () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT),
                () -> assertThat(order.getItems().get(0).getProductName()).isEqualTo("상품"),
                () -> assertThat(order.getItems().get(0).getUnitPrice()).isEqualTo(1_000L),
                () -> assertThat(product.getStock()).isEqualTo(10),
                () -> assertThat(pointService.getPoint(USER_ID).getBalance()).isEqualTo(10_000L)
            );
        }

        @DisplayName("없거나 삭제된 상품이 있으면, 처음 만난 상품을 알리는 PRODUCT_NOT_FOUND 예외가 발생하고 주문이 만들어지지 않는다. (ORD-01)")
        @Test
        void throwsProductNotFound_whenProductIsMissingOrDeleted() {
            // arrange
            Product active = saveProduct(1_000L, 10);
            Product deleted = saveProduct(1_000L, 10);
            deleted.delete();

            // act
            CoreException result = assertThrows(CoreException.class, () -> createOrder(line(active, 1), line(deleted, 1)));

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND),
                () -> assertThat(failedProductId(result)).isEqualTo(deleted.getId()),
                () -> assertThat(orderRepository.count()).isZero()
            );
        }
    }

    @DisplayName("내 주문 상세를 조회할 때, ")
    @Nested
    class GetMyOrder {
        @DisplayName("주문한 상품이 이후 삭제되었으면, 그 품목은 판매 여부가 false 이고 나머지는 true 다.")
        @Test
        void marksDeletedProductAsNotOnSale() {
            // arrange
            Product active = saveProduct(1_000L, 10);
            Product deleted = saveProduct(1_000L, 10);
            OrderInfo created = orderFacade.createOrder(USER_ID, List.of(
                new OrderRequestLine(active.getId(), 1),
                new OrderRequestLine(deleted.getId(), 1)
            ));
            deleted.delete();

            // act
            OrderDetailInfo result = orderFacade.getMyOrder(USER_ID, created.id());

            // assert
            assertThat(result.items())
                .extracting(OrderDetailInfo.Item::productId, OrderDetailInfo.Item::onSale)
                .containsExactly(tuple(active.getId(), true), tuple(deleted.getId(), false));
        }
    }
}
