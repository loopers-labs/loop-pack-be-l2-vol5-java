package com.loopers.application.brand;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.Order;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

// 테스트 메서드에 @Transactional을 붙이지 않는다 — 테스트 자동 rollback을 서비스 rollback으로 오인하지 않기 위해서.
@SpringBootTest
class BrandRemovalTransactionTest {

    @Autowired
    private BrandAdminFacade brandAdminFacade;

    @MockitoSpyBean
    private BrandRepository brandRepository;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private UserJpaRepository userJpaRepository;

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

    @DisplayName("상품 삭제 SQL이 DB에 나간 뒤 브랜드 저장에서 실패하면, 브랜드·연결 상품 변경이 전부 rollback되고 다른 대상과 과거 주문은 그대로다.")
    @Test
    void rollsBackBrandAndLinkedProducts_whenBrandSaveFailsAfterProductUpdatesAreFlushed() {
        // arrange — 각 save가 자기 트랜잭션으로 commit된다
        BrandModel brand = brandJpaRepository.save(new BrandModel("나이키", "스포츠 브랜드", "신발/의류"));
        BrandModel otherBrand = brandJpaRepository.save(new BrandModel("아디다스", "스포츠 브랜드", "신발/의류"));
        ProductModel inStock = productJpaRepository.save(new ProductModel("에어맥스", 1000L, brand.getId(), 10));
        ProductModel soldOut = productJpaRepository.save(new ProductModel("품절상품", 1000L, brand.getId(), 0));
        ProductModel otherProduct = productJpaRepository.save(new ProductModel("울트라부스트", 1000L, otherBrand.getId(), 10));
        Long userId = userJpaRepository.save(new UserModel()).getId();
        Order pastOrder = new Order(userId, List.of(new Order.OrderItemDraft(inStock.getId(), 2, 1000L)));
        pastOrder.confirm(2000L);
        Long pastOrderId = orderJpaRepository.save(pastOrder).getId();

        // 브랜드 저장 시점에: 지금까지의 변경(상품 UPDATE)을 실제로 DB에 보내고, 그게 DB에 도달했는지 같은 트랜잭션 안에서 센 뒤 실패시킨다.
        AtomicLong deletedProductsSeenInsideTransaction = new AtomicLong(-1);
        doAnswer(invocation -> {
            entityManager.flush();
            Number count = (Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM product WHERE brand_id = :brandId AND deleted_at IS NOT NULL")
                .setParameter("brandId", brand.getId())
                .getSingleResult();
            deletedProductsSeenInsideTransaction.set(count.longValue());
            throw new IllegalStateException("브랜드 저장 실패 주입");
        }).when(brandRepository).save(any(BrandModel.class));

        // act
        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> brandAdminFacade.deleteBrand(brand.getId())
        );

        // assert — 트랜잭션 안에서는 상품 2개의 삭제가 DB에 반영돼 있었다(실제 SQL이 나갔다)
        assertThat(exception.getMessage()).isEqualTo("브랜드 저장 실패 주입");
        assertThat(deletedProductsSeenInsideTransaction.get()).isEqualTo(2L);

        // assert — 트랜잭션이 끝난 뒤 새로 읽은 DB: 전부 작업 전 상태
        Order reloadedOrder = orderJpaRepository.findById(pastOrderId).orElseThrow();
        assertAll(
            () -> assertThat(brandJpaRepository.findById(brand.getId()).orElseThrow().getDeletedAt()).isNull(),
            () -> assertThat(productJpaRepository.findById(inStock.getId()).orElseThrow().getDeletedAt()).isNull(),
            () -> assertThat(productJpaRepository.findById(soldOut.getId()).orElseThrow().getDeletedAt()).isNull(),
            () -> assertThat(brandJpaRepository.findById(otherBrand.getId()).orElseThrow().getDeletedAt()).isNull(),
            () -> assertThat(productJpaRepository.findById(otherProduct.getId()).orElseThrow().getDeletedAt()).isNull(),
            () -> assertThat(reloadedOrder.getPaidAmount()).isEqualTo(2000L),
            () -> assertThat(reloadedOrder.getItems().get(0).getUnitPrice()).isEqualTo(1000L),
            () -> assertThat(reloadedOrder.getItems().get(0).getQuantity()).isEqualTo(2)
        );
    }
}
