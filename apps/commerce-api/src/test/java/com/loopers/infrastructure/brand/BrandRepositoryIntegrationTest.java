package com.loopers.infrastructure.brand;

import com.loopers.support.error.DomainError;
import com.loopers.support.error.DomainException;
import jakarta.persistence.EntityManager;
import com.loopers.application.brand.BrandFacade;
import com.loopers.application.product.ProductFacade;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.product.Price;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class BrandRepositoryIntegrationTest {

    private final BrandService brandService;
    private final ProductFacade productFacade;
    private final BrandRepository brandRepository;
    private final DatabaseCleanUp databaseCleanUp;
    private final BrandFacade brandFacade;
    private final EntityManager entityManager;

    @Autowired
    BrandRepositoryIntegrationTest(
        BrandService brandService,
        ProductFacade productFacade,
        BrandRepository brandRepository,
        DatabaseCleanUp databaseCleanUp,
        BrandFacade brandFacade,
        EntityManager entityManager
    ) {
        this.brandService = brandService;
        this.productFacade = productFacade;
        this.brandRepository = brandRepository;
        this.databaseCleanUp = databaseCleanUp;
        this.brandFacade = brandFacade;
        this.entityManager = entityManager;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("등록한 브랜드가 저장되고, 다시 읽어도 같은 값이다.")
    @Test
    void persistsAcrossReads() {
        Long id = brandFacade.register("무신사", "패션 플랫폼").getId();

        Brand found = brandService.get(id);
        assertThat(found.getId()).isEqualTo(id);
        assertThat(found.getName()).isEqualTo("무신사");
        assertThat(found.getDescription()).isEqualTo("패션 플랫폼");
    }

    @DisplayName("설명은 없어도 저장된다.")
    @Test
    void persistsWithoutDescription() {
        Long id = brandFacade.register("무신사", null).getId();

        assertThat(brandService.get(id).getDescription()).isNull();
    }

    @DisplayName("4.3 · 저장소의 기본은 살아 있는 것뿐이다. 삭제된 행은 이름으로 밝혀야 보인다.")
    @Test
    void repositoryHidesDeletedByDefault() {
        Long id = brandFacade.register("무신사", "패션 플랫폼").getId();
        brandFacade.delete(id);

        assertThat(brandRepository.findById(id))
            .as("기본 조회는 삭제된 것을 주지 않는다 — 호출부가 거르기를 잊을 수 없다")
            .isEmpty();
        assertThatThrownBy(() -> brandFacade.update(id, "무신사", "다시"))
            .as("findByIdForUpdate 도 삭제된 브랜드를 주지 않는다")
            .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("error", DomainError.BRAND_NOT_FOUND);
        assertThat(deletedRowCount(id))
            .as("논리 삭제는 행을 지우지 않는다")
            .isEqualTo(1L);
        assertThatThrownBy(() -> brandService.get(id)).isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("error", DomainError.BRAND_NOT_FOUND);
    }

    @DisplayName("BRAND-004 · 살아 있는 상품이 있으면 브랜드를 삭제할 수 없다. 재고 0인 상품도 포함한다.")
    @Test
    void rejectsDeleteWhenAliveProductExists() {
        Long brandId = brandFacade.register("무신사", "패션 플랫폼").getId();
        productFacade.register(brandId, "코트", Price.of(129_000));

        assertThatThrownBy(() -> brandFacade.delete(brandId)).isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("error", DomainError.BRAND_HAS_PRODUCTS);
        assertThat(brandService.get(brandId).getName()).isEqualTo("무신사");
    }

    @DisplayName("BRAND-004 · 연결된 상품이 전부 삭제되었다면 브랜드도 삭제된다.")
    @Test
    void allowsDeleteWhenAllProductsDeleted() {
        Long brandId = brandFacade.register("무신사", "패션 플랫폼").getId();
        Long productId = productFacade.register(brandId, "코트", Price.of(129_000)).getId();
        productFacade.delete(productId);

        brandFacade.delete(brandId);

        assertThatThrownBy(() -> brandService.get(brandId)).isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("error", DomainError.BRAND_NOT_FOUND);
    }

    @DisplayName("다른 브랜드의 상품은 이 브랜드의 삭제를 막지 않는다.")
    @Test
    void ignoresOtherBrandsProducts() {
        Long brandId = brandFacade.register("무신사", "패션 플랫폼").getId();
        Long other = brandFacade.register("29CM", "셀렉트샵").getId();
        productFacade.register(other, "코트", Price.of(129_000));

        brandFacade.delete(brandId);

        assertThatThrownBy(() -> brandService.get(brandId)).isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("error", DomainError.BRAND_NOT_FOUND);
    }

    @DisplayName("BRAND-004 · 삭제 도중 같은 브랜드로 상품이 등록되어도 둘 중 하나만 성공한다.")
    @Test
    void doesNotLeaveAliveProductUnderDeletedBrand() throws InterruptedException {
        Long brandId = brandFacade.register("무신사", "패션 플랫폼").getId();

        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.atomic.AtomicBoolean deleted = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean registered = new java.util.concurrent.atomic.AtomicBoolean();

        executor.submit(() -> {
            try {
                start.await();
                brandFacade.delete(brandId);
                deleted.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
            } finally {
                done.countDown();
            }
        });
        executor.submit(() -> {
            try {
                start.await();
                productFacade.register(brandId, "코트", Price.of(129_000));
                registered.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
            } finally {
                done.countDown();
            }
        });

        start.countDown();
        done.await(30, java.util.concurrent.TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(deleted.get() && registered.get()).isFalse();
    }

    private long deletedRowCount(Long id) {
        return ((Number) entityManager
            .createNativeQuery("SELECT COUNT(*) FROM brand WHERE id = :id AND deleted_at IS NOT NULL")
            .setParameter("id", id)
            .getSingleResult()).longValue();
    }
}
