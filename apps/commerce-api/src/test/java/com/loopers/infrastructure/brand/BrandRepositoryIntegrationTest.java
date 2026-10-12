package com.loopers.infrastructure.brand;

import com.loopers.support.error.DomainError;
import com.loopers.support.error.DomainException;
import jakarta.persistence.EntityManager;
import com.loopers.application.brand.BrandFacade;
import com.loopers.application.product.ProductFacade;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.common.Quantity;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.ProductService;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
    private final ProductService productService;

    @Autowired
    BrandRepositoryIntegrationTest(
        BrandService brandService,
        ProductFacade productFacade,
        BrandRepository brandRepository,
        DatabaseCleanUp databaseCleanUp,
        BrandFacade brandFacade,
        EntityManager entityManager,
        ProductService productService
    ) {
        this.productService = productService;
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

    @DisplayName("BRAND-004 · 브랜드를 지우면 연결된 살아 있는 상품이 재고 0인 것까지 함께 삭제된다.")
    @Test
    void deletesAliveProductsTogether() {
        Long brandId = brandFacade.register("무신사", "패션 플랫폼").getId();
        Long soldOut = productFacade.register(brandId, "코트", Price.of(129_000)).getId();
        Long inStock = productFacade.register(brandId, "니트", Price.of(59_000)).getId();
        productFacade.adjustStock(inStock, Quantity.of(5));

        brandFacade.delete(brandId);

        assertThatThrownBy(() -> brandService.get(brandId)).isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("error", DomainError.BRAND_NOT_FOUND);
        for (Long productId : List.of(soldOut, inStock)) {
            assertThatThrownBy(() -> productService.get(productId)).isInstanceOf(DomainException.class)
                    .hasFieldOrPropertyWithValue("error", DomainError.PRODUCT_NOT_FOUND);
            assertThatThrownBy(() -> productFacade.adjustStock(productId, Quantity.of(1)))
                .as("삭제된 상품의 재고 변경은 거절된다")
                .isInstanceOf(DomainException.class)
                    .hasFieldOrPropertyWithValue("error", DomainError.PRODUCT_NOT_FOUND);
        }
        assertThat(deletedProductCount(brandId))
            .as("논리 삭제는 행을 지우지 않는다")
            .isEqualTo(2L);
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

    @DisplayName("다른 브랜드와 그 상품은 이 브랜드의 삭제에 영향받지 않는다. 연결 상품이 없는 브랜드도 지워진다.")
    @Test
    void leavesOtherBrandsUntouched() {
        Long brandId = brandFacade.register("무신사", "패션 플랫폼").getId();
        Long other = brandFacade.register("29CM", "셀렉트샵").getId();
        Long otherProduct = productFacade.register(other, "코트", Price.of(129_000)).getId();

        brandFacade.delete(brandId);

        assertThatThrownBy(() -> brandService.get(brandId)).isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("error", DomainError.BRAND_NOT_FOUND);
        assertThat(brandService.get(other).getName()).isEqualTo("29CM");
        assertThat(productService.get(otherProduct).getName()).isEqualTo("코트");
    }

    @DisplayName("BRAND-004 · 삭제와 같은 브랜드의 상품 등록이 겹쳐도, 삭제된 브랜드 아래 살아 있는 상품은 남지 않는다.")
    @Test
    void doesNotLeaveAliveProductUnderDeletedBrand() throws InterruptedException {
        Long brandId = brandFacade.register("무신사", "패션 플랫폼").getId();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();
        try {
            executor.submit(() -> {
                try {
                    start.await();
                    brandFacade.delete(brandId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                    unexpected.add(e);
                } finally {
                    done.countDown();
                }
            });
            executor.submit(() -> {
                try {
                    start.await();
                    productFacade.register(brandId, "코트", Price.of(129_000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (DomainException e) {
                    if (e.error() != DomainError.BRAND_NOT_AVAILABLE) {
                        unexpected.add(e);
                    }
                } catch (RuntimeException e) {
                    unexpected.add(e);
                } finally {
                    done.countDown();
                }
            });

            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).as("두 요청이 제한 시간 안에 끝난다").isTrue();
        } finally {
            executor.shutdownNow();
        }

        assertThat(unexpected).as("삭제는 실패하지 않고, 등록은 성공하거나 BRAND_NOT_AVAILABLE 로만 거절된다").isEmpty();
        assertThat(deletedRowCount(brandId)).isEqualTo(1L);
        assertThat(aliveProductCount(brandId)).isZero();
    }

    private long deletedRowCount(Long id) {
        return ((Number) entityManager
            .createNativeQuery("SELECT COUNT(*) FROM brand WHERE id = :id AND deleted_at IS NOT NULL")
            .setParameter("id", id)
            .getSingleResult()).longValue();
    }

    private long deletedProductCount(Long brandId) {
        return ((Number) entityManager
            .createNativeQuery("SELECT COUNT(*) FROM product WHERE brand_id = :brandId AND deleted_at IS NOT NULL")
            .setParameter("brandId", brandId)
            .getSingleResult()).longValue();
    }

    private long aliveProductCount(Long brandId) {
        return ((Number) entityManager
            .createNativeQuery("SELECT COUNT(*) FROM product WHERE brand_id = :brandId AND deleted_at IS NULL")
            .setParameter("brandId", brandId)
            .getSingleResult()).longValue();
    }
}
