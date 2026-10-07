package com.loopers.domain.brand;

import com.loopers.application.brand.BrandFacade;
import com.loopers.application.like.LikeFacade;
import com.loopers.application.order.OrderConfirmFacade;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.point.PointFacade;
import com.loopers.domain.order.OrderItemCommand;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.fixture.BrandFixture;
import com.loopers.fixture.ProductFixture;
import com.loopers.fixture.UserFixture;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.like.LikeJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * 브랜드 일괄 삭제에서 실제 Product bulk UPDATE 가 실행된 뒤 다음 Brand 저장 경계에서 실패하면
 * 이번 요청의 Brand·Product 변경이 모두 rollback 되는지 확인한다.
 * 테스트 전체를 부모 트랜잭션으로 감싸지 않고, 준비 데이터를 먼저 commit 한 뒤 실제 BrandFacade 프록시를 호출한다.
 */
@DisplayName("브랜드 일괄 삭제는 bulk UPDATE 이후 실패해도 Brand·Product 변경을 모두 rollback 한다.")
@SpringBootTest
class BrandRemovalTransactionIntegrationTest {

    private static final String INJECTED_FAILURE = "bulk 이후 Brand 저장 경계에서 주입한 실패";

    @MockitoSpyBean
    private BrandRepository brandRepository;

    @Autowired
    private BrandFacade brandFacade;
    @Autowired
    private BrandFixture brandFixture;
    @Autowired
    private ProductFixture productFixture;
    @Autowired
    private UserFixture userFixture;
    @Autowired
    private PointFacade pointFacade;
    @Autowired
    private LikeFacade likeFacade;
    @Autowired
    private OrderFacade orderFacade;
    @Autowired
    private OrderConfirmFacade orderConfirmFacade;
    @Autowired
    private BrandJpaRepository brandJpaRepository;
    @Autowired
    private ProductJpaRepository productJpaRepository;
    @Autowired
    private LikeJpaRepository likeJpaRepository;
    @Autowired
    private OrderJpaRepository orderJpaRepository;
    @PersistenceContext
    private EntityManager entityManager;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    /** 활성 조건 없이 새 트랜잭션에서 읽은 행의 상태. 삭제·수정 시각을 포함한다. */
    private List<Object> productRow(Long productId) {
        ProductModel product = productJpaRepository.findById(productId).orElseThrow();
        return Arrays.asList(product.getBrandId(), product.getName(), product.getPrice().toWon(),
            product.getStockQuantity(), product.getCreatedAt(), product.getUpdatedAt(), product.getDeletedAt());
    }

    private List<Object> brandRow(Long brandId) {
        BrandModel brand = brandJpaRepository.findById(brandId).orElseThrow();
        return Arrays.asList(brand.getName(), brand.getCreatedAt(), brand.getUpdatedAt(), brand.getDeletedAt());
    }

    private List<Object> orderRow(Long orderId) {
        OrderModel order = orderJpaRepository.findWithItemsById(orderId).orElseThrow();
        List<List<Object>> items = order.getItems().stream()
            .map(item -> List.<Object>of(item.getProductId(), item.getQuantity(), item.getUnitPrice().toWon()))
            .toList();
        return Arrays.asList(order.getStatus(), order.getOrderTotal().toWon(), order.getUsedPointAmount(),
            order.getPaymentAmount() != null ? order.getPaymentAmount().toWon() : null, items);
    }

    private List<List<Object>> likeRows() {
        return likeJpaRepository.findAll().stream()
            .map(like -> List.<Object>of(like.getId(), like.getUserId(), like.getProductId()))
            .toList();
    }

    @DisplayName("bulk UPDATE 가 현재 트랜잭션에 반영된 뒤 Brand 저장에서 예외가 나면, 예외를 전파하고 새 조회에서 모든 대상이 작업 전 상태다.")
    @Test
    void rollsBackBrandAndProductsWhenBrandSaveFailsAfterBulk() {
        BrandModel nike = brandFixture.createBrand("나이키");
        BrandModel adidas = brandFixture.createBrand("아디다스");
        ProductModel shoes = productFixture.createProduct(nike.getId(), "운동화", 10_000L, 3L);
        ProductModel soldOut = productFixture.createProduct(nike.getId(), "품절 운동화", 20_000L, 0L);
        ProductModel discontinued = productFixture.createProduct(nike.getId(), "단종 운동화", 30_000L, 5L);
        productFixture.deleteProduct(discontinued.getId());
        ProductModel slipper = productFixture.createProduct(adidas.getId(), "삼선 슬리퍼", 5_000L, 4L);

        UserModel user = userFixture.createUserWithPoint();
        pointFacade.charge(user.getId(), 50_000L);
        likeFacade.like(user.getId(), shoes.getId());
        OrderModel pastOrder = orderFacade.create(user.getId(), List.of(new OrderItemCommand(shoes.getId(), 1L)));
        orderConfirmFacade.confirm(user.getId(), pastOrder.getId());

        List<Object> nikeBefore = brandRow(nike.getId());
        List<Object> adidasBefore = brandRow(adidas.getId());
        List<Object> shoesBefore = productRow(shoes.getId());
        List<Object> soldOutBefore = productRow(soldOut.getId());
        List<Object> discontinuedBefore = productRow(discontinued.getId());
        List<Object> slipperBefore = productRow(slipper.getId());
        List<Object> orderBefore = orderRow(pastOrder.getId());
        List<List<Object>> likesBefore = likeRows();

        AtomicBoolean injectedInTransaction = new AtomicBoolean(false);
        AtomicLong deletedProductsSeenAtInjection = new AtomicLong(-1L);
        doAnswer(invocation -> {
            injectedInTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            Number deletedCount = (Number) entityManager.createNativeQuery(
                    "SELECT COUNT(*) FROM product WHERE brand_id = :brandId AND deleted_at IS NOT NULL")
                .setParameter("brandId", nike.getId())
                .getSingleResult();
            deletedProductsSeenAtInjection.set(deletedCount.longValue());
            throw new IllegalStateException(INJECTED_FAILURE);
        }).when(brandRepository).save(any(BrandModel.class));

        assertThatThrownBy(() -> brandFacade.delete(nike.getId()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage(INJECTED_FAILURE);

        ZonedDateTime nikeDeletedAtAfter = brandJpaRepository.findById(nike.getId()).orElseThrow().getDeletedAt();
        assertAll(
            () -> assertThat(injectedInTransaction).isTrue(),
            () -> assertThat(deletedProductsSeenAtInjection).hasValue(3L),
            () -> assertThat(nikeDeletedAtAfter).isNull(),
            () -> assertThat(brandRow(nike.getId())).isEqualTo(nikeBefore),
            () -> assertThat(brandRow(adidas.getId())).isEqualTo(adidasBefore),
            () -> assertThat(productRow(shoes.getId())).isEqualTo(shoesBefore),
            () -> assertThat(productRow(soldOut.getId())).isEqualTo(soldOutBefore),
            () -> assertThat(productRow(discontinued.getId())).isEqualTo(discontinuedBefore),
            () -> assertThat(productRow(slipper.getId())).isEqualTo(slipperBefore),
            () -> assertThat(orderRow(pastOrder.getId())).isEqualTo(orderBefore),
            () -> assertThat(likeRows()).isEqualTo(likesBefore)
        );
    }
}
