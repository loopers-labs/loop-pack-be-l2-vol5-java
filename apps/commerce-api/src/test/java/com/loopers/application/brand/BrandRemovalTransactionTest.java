package com.loopers.application.brand;

import com.loopers.application.user.UserRegistrationService;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.like.LikeModel;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.infrastructure.like.LikeJpaRepository;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderStatus;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.product.ProductRepositoryImpl;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest
@AutoConfigureMockMvc
class BrandRemovalTransactionTest {
    @Autowired private UserRegistrationService registration;
    @Autowired private BrandFacade facade;
    @Autowired private MockMvc mvc;
    @Autowired private UserJpaRepository users;
    @Autowired private LikeJpaRepository likes;
    @Autowired private BrandJpaRepository brands;
    @Autowired private ProductJpaRepository products;
    @Autowired private OrderJpaRepository orders;
    @Autowired private EntityManager entityManager;
    @Autowired private TransactionTemplate transaction;
    @Autowired private DatabaseCleanUp cleanUp;
    @MockitoSpyBean private ProductRepositoryImpl productRepository;

    @AfterEach
    void clean() {
        reset(productRepository);
        cleanUp.truncateAllTables();
    }

    @Test
    void deletesAllActiveProductsIncludingZeroStockAndPreservesOtherData() {
        Fixture f = prepare();
        facade.delete(f.brandId());
        verifyState(f, true);
    }

    @Test
    void rollsBackActualSqlChangesWhenNextProductSaveFails() {
        Fixture f = prepare();
        AtomicInteger saves = new AtomicInteger();
        doAnswer(invocation -> {
            if (saves.incrementAndGet() == 2) {
                throw new IllegalStateException("test: next product save failed");
            }
            Object result = invocation.callRealMethod();
            entityManager.flush();
            Number changed = (Number) entityManager.createNativeQuery(
                "select count(*) from product where brand_id = :id and deleted_at is not null")
                .setParameter("id", f.brandId()).getSingleResult();
            assertThat(changed.intValue()).isGreaterThan(1); // 기존 삭제 상품 + 실제 UPDATE
            return result;
        }).when(productRepository).save(any(ProductModel.class));

        assertThatThrownBy(() -> facade.delete(f.brandId()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("test: next product save failed");
        assertThat(saves.get()).isEqualTo(2);
        verifyState(f, false); // 서비스 트랜잭션 종료 후 새 트랜잭션에서 조회
    }

    @Test
    void deletesBrandWithoutProducts() {
        BrandModel brand = brands.save(new BrandModel("empty", null));
        facade.delete(brand.getId());
        assertThat(brands.findById(brand.getId()).orElseThrow().getDeletedAt()).isNotNull();
    }

    @Test
    void rejectsMissingAndAlreadyDeletedBrands() {
        BrandModel brand = brands.save(new BrandModel("deleted", null));
        facade.delete(brand.getId());
        assertThatThrownBy(() -> facade.delete(brand.getId()))
            .isInstanceOf(CoreException.class).extracting("errorType").isEqualTo(ErrorType.NOT_FOUND);
        assertThatThrownBy(() -> facade.delete(Long.MAX_VALUE))
            .isInstanceOf(CoreException.class).extracting("errorType").isEqualTo(ErrorType.NOT_FOUND);
    }

    @Test
    void deletionConnectsToCustomerRestrictionsAndPreservesOrderHistory() throws Exception {
        Fixture f = prepare();
        var user = registration.register();
        likes.save(new LikeModel(user.getId(), f.j()));
        OrderModel draft = orders.save(new OrderModel(user.getId(),
            List.of(new OrderItem(f.j(), "J", 2000, 1))));

        mvc.perform(delete("/api-admin/v1/brands/" + f.brandId()).header("X-USER-ROLE", "ADMIN"))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/brands/" + f.brandId())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/products/" + f.j())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/products"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].id").value(f.m()));
        mvc.perform(get("/api/v1/users/" + user.getId() + "/likes").header("X-USER-ID", user.getId()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(post("/api/v1/products/" + f.j() + "/likes").header("X-USER-ID", user.getId()))
            .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/orders").header("X-USER-ID", user.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"productId\":" + f.j() + ",\"quantity\":1}]}"))
            .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/orders/" + draft.getId() + "/confirm").header("X-USER-ID", user.getId()))
            .andExpect(status().isNotFound());
        mvc.perform(put("/api-admin/v1/products/" + f.j()).header("X-USER-ROLE", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"changed\",\"price\":3000}"))
            .andExpect(status().isNotFound());
        mvc.perform(put("/api-admin/v1/products/" + f.j() + "/stock").header("X-USER-ROLE", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":10}"))
            .andExpect(status().isNotFound());
        assertThat(likes.findByUserIdAndProductId(user.getId(), f.j())).isPresent();
        mvc.perform(delete("/api/v1/products/" + f.j() + "/likes").header("X-USER-ID", user.getId()))
            .andExpect(status().isNoContent());
        assertThat(likes.findByUserIdAndProductId(user.getId(), f.j())).isEmpty();
        mvc.perform(get("/api/v1/orders/" + f.orderId()).header("X-USER-ID", 1L))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.paidAmount").value(4000));
        assertThat(orders.findById(draft.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.DRAFT);
        verifyState(f, true);
    }

    @Test
    void deniedAdminRequestsLeaveDatabaseUnchanged() throws Exception {
        Fixture f = prepare();
        mvc.perform(delete("/api-admin/v1/brands/" + f.brandId())).andExpect(status().isForbidden());
        mvc.perform(delete("/api-admin/v1/brands/" + f.brandId()).header("X-USER-ROLE", "USER"))
            .andExpect(status().isForbidden());
        verifyState(f, false);
    }

    @Test
    void rejectsAdminProductCreationUnderDeletedBrand() throws Exception {
        Fixture f = prepare();
        facade.delete(f.brandId());
        mvc.perform(post("/api-admin/v1/products").header("X-USER-ROLE", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"brandId\":" + f.brandId() + ",\"name\":\"new\",\"price\":1000,\"initialStockQuantity\":3}"))
            .andExpect(status().isNotFound());
        assertThat(products.findAll().stream().noneMatch(p -> p.getName().equals("new"))).isTrue();
    }

    private Fixture prepare() {
        return transaction.execute(status -> {
            BrandModel a = brands.save(new BrandModel("A", null));
            BrandModel b = brands.save(new BrandModel("B", null));
            ProductModel j = products.save(new ProductModel(a.getId(), "J", 2000, 5));
            ProductModel k = products.save(new ProductModel(a.getId(), "K", 1000, 0));
            ProductModel l = new ProductModel(a.getId(), "L", 1000, 1);
            l.markDeleted();
            l = products.save(l);
            ProductModel m = products.save(new ProductModel(b.getId(), "M", 3000, 7));
            OrderModel order = new OrderModel(1L, List.of(new OrderItem(j.getId(), "J", 2000, 2)));
            order.confirm();
            order = orders.save(order);
            entityManager.flush();
            entityManager.clear();
            return new Fixture(a.getId(), b.getId(), j.getId(), k.getId(), l.getId(), m.getId(),
                order.getId(), products.findById(l.getId()).orElseThrow().getDeletedAt());
        });
    }

    private void verifyState(Fixture f, boolean deleted) {
        transaction.executeWithoutResult(status -> {
            entityManager.clear();
            assertThat(brands.findById(f.brandId()).orElseThrow().getDeletedAt() != null).isEqualTo(deleted);
            assertThat(products.findById(f.j()).orElseThrow().getDeletedAt() != null).isEqualTo(deleted);
            assertThat(products.findById(f.k()).orElseThrow().getDeletedAt() != null).isEqualTo(deleted);
            assertThat(products.findById(f.l()).orElseThrow().getDeletedAt()).isEqualTo(f.oldDeletedAt());
            assertThat(brands.findById(f.otherBrandId()).orElseThrow().getDeletedAt()).isNull();
            assertThat(products.findById(f.m()).orElseThrow().getDeletedAt()).isNull();
            assertThat(products.findById(f.m()).orElseThrow().getStockQuantity()).isEqualTo(7);
            assertThat(products.findById(f.j()).orElseThrow().getStockQuantity()).isEqualTo(5);
            assertThat(products.findById(f.k()).orElseThrow().getStockQuantity()).isZero();
            OrderModel order = orders.findById(f.orderId()).orElseThrow();
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(order.getTotalAmount()).isEqualTo(4000);
            assertThat(order.getPaidAmount()).isEqualTo(4000);
            assertThat(order.getItems()).singleElement().satisfies(item -> {
                assertThat(item.getProductId()).isEqualTo(f.j());
                assertThat(item.getProductName()).isEqualTo("J");
                assertThat(item.getUnitPrice()).isEqualTo(2000);
                assertThat(item.getQuantity()).isEqualTo(2);
            });
        });
    }

    private record Fixture(Long brandId, Long otherBrandId, Long j, Long k, Long l, Long m,
                           Long orderId, java.time.ZonedDateTime oldDeletedAt) {}
}
