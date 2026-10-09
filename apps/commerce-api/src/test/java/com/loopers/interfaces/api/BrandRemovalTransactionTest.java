package com.loopers.interfaces.api;

import com.loopers.application.order.OrderAdminInfo;
import com.loopers.application.order.OrderFacade;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class BrandRemovalTransactionTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private OrderJpaRepository orderJpaRepository;

    @Autowired
    private OrderFacade orderFacade;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @PersistenceContext
    private EntityManager entityManager;

    @MockitoSpyBean
    private BrandRepository brandRepository;

    private BrandModel brand;
    private ProductModel inStockProduct;
    private ProductModel soldOutProduct;
    private BrandModel otherBrand;
    private ProductModel otherBrandProduct;
    private Long pastOrderId;
    private OrderAdminInfo pastOrderBefore;

    @BeforeEach
    void setUp() {
        brand = brandJpaRepository.save(new BrandModel("나이키"));
        inStockProduct = productJpaRepository.save(new ProductModel(brand.getId(), "runner", 10_000L, 5));
        soldOutProduct = productJpaRepository.save(new ProductModel(brand.getId(), "slipper", 5_000L, 0));
        otherBrand = brandJpaRepository.save(new BrandModel("아디다스"));
        otherBrandProduct = productJpaRepository.save(new ProductModel(otherBrand.getId(), "classic", 20_000L, 3));

        OrderModel pastOrder = new OrderModel(1L, List.of(new OrderItem(inStockProduct.getId(), 2, 10_000L)));
        pastOrder.confirm(20_000L);
        pastOrderId = orderJpaRepository.save(pastOrder).getId();
        pastOrderBefore = orderFacade.getOrderForAdmin(pastOrderId);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("브랜드를 삭제하면, 재고 0인 상품까지 연결 상품이 함께 삭제되고 다른 브랜드·상품과 과거 주문은 유지된다.")
    @Test
    void deletesBrandAndLinkedProducts_andKeepsOthers() throws Exception {
        // act
        mvc.perform(delete("/api-admin/v1/brands/" + brand.getId())
                .with(user("admin").roles("ADMIN"))
                .with(csrf()))
            .andExpect(status().isOk());

        // assert
        assertThat(isBrandDeleted(brand)).isTrue();
        assertThat(isProductDeleted(inStockProduct)).isTrue();
        assertThat(isProductDeleted(soldOutProduct)).isTrue();
        assertThat(isBrandDeleted(otherBrand)).isFalse();
        assertThat(isProductDeleted(otherBrandProduct)).isFalse();
        assertThat(stockOf(inStockProduct)).isEqualTo(5);
        assertThat(orderFacade.getOrderForAdmin(pastOrderId)).isEqualTo(pastOrderBefore);
    }

    @DisplayName("변경이 DB로 나간 뒤 브랜드 저장에서 실패하면, 브랜드와 상품 변경이 전부 취소된다.")
    @Test
    void rollsBackEverything_whenBrandSaveFailsAfterChangesAreFlushed() throws Exception {
        // arrange: 브랜드를 저장하기 직전에, 앞선 변경을 flush한 뒤 예외를 던진다
        AtomicBoolean failureInjected = new AtomicBoolean(false);
        AtomicBoolean hadPendingChanges = new AtomicBoolean(false);
        doAnswer(invocation -> {
            failureInjected.set(true);
            hadPendingChanges.set(entityManager.unwrap(Session.class).isDirty());
            entityManager.flush();
            throw new IllegalStateException("브랜드 저장 실패");
        }).when(brandRepository).save(any(BrandModel.class));

        // act
        mvc.perform(delete("/api-admin/v1/brands/" + brand.getId())
                .with(user("admin").roles("ADMIN"))
                .with(csrf()))
            .andExpect(status().isInternalServerError());

        // assert: 실패 지점에서 상품 변경이 실제로 나갔던 것을 확인하고
        assertThat(failureInjected).isTrue();
        assertThat(hadPendingChanges).isTrue();

        // assert: 요청이 끝난 뒤 새로 조회한 값이 전부 원래대로다
        assertThat(isBrandDeleted(brand)).isFalse();
        assertThat(isProductDeleted(inStockProduct)).isFalse();
        assertThat(isProductDeleted(soldOutProduct)).isFalse();
        assertThat(isBrandDeleted(otherBrand)).isFalse();
        assertThat(isProductDeleted(otherBrandProduct)).isFalse();
        assertThat(stockOf(inStockProduct)).isEqualTo(5);
        assertThat(stockOf(soldOutProduct)).isEqualTo(0);
        assertThat(orderFacade.getOrderForAdmin(pastOrderId)).isEqualTo(pastOrderBefore);
    }

    private boolean isBrandDeleted(BrandModel target) {
        return brandJpaRepository.findById(target.getId()).orElseThrow().getDeletedAt() != null;
    }

    private boolean isProductDeleted(ProductModel target) {
        return productJpaRepository.findById(target.getId()).orElseThrow().getDeletedAt() != null;
    }

    private int stockOf(ProductModel target) {
        return productJpaRepository.findById(target.getId()).orElseThrow().getStock();
    }
}
