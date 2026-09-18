package com.loopers.domain.brand;

import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongPredicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

class BrandDeletionServiceTest {

    private static final long BRAND_ID = 10L;
    private static final ZonedDateTime DELETED_AT = ZonedDateTime.parse("2026-09-18T12:00:00+09:00");

    @DisplayName("BRAND-DELETE-01: 미삭제 상품이 없으면 조회한 브랜드를 삭제 상태로 변경한다.")
    @Test
    void deletesBrandWithoutNonDeletedProducts() {
        Brand brand = new Brand("Original Brand");
        Map<Long, Brand> brands = Map.of(BRAND_ID, brand);
        BrandDeletionService service = new BrandDeletionService(
            lookupRepository(brands), productLookup(id -> false));

        Brand result = service.delete(BRAND_ID, DELETED_AT);

        assertAll(
            () -> assertThat(result).isSameAs(brand),
            () -> assertThat(brand.isDeleted()).isTrue(),
            () -> assertThat(brand.getDeletedAt()).isEqualTo(DELETED_AT),
            () -> assertThat(brand.getName()).isEqualTo("Original Brand")
        );
    }

    @DisplayName("BRAND-DELETE-02: 미삭제 상품이 존재하면 삭제를 거절하고 브랜드를 보존한다.")
    @Test
    void rejectsDeletionWhenNonDeletedProductsExist() {
        Brand brand = new Brand("Original Brand");
        Map<Long, Brand> brands = Map.of(BRAND_ID, brand);
        BrandDeletionService service = new BrandDeletionService(
            lookupRepository(brands), productLookup(id -> true));

        assertAll(
            () -> assertDeletionRejected(() -> service.delete(BRAND_ID, DELETED_AT),
                BrandDeletionException.Reason.NON_DELETED_PRODUCTS_EXIST),
            () -> assertThat(brand.isDeleted()).isFalse(),
            () -> assertThat(brand.getDeletedAt()).isNull(),
            () -> assertThat(brand.getName()).isEqualTo("Original Brand")
        );
    }

    @DisplayName("BRAND-DELETE-03: 없는 브랜드는 상품 조회 없이 거절하고 다른 브랜드를 유지한다.")
    @Test
    void rejectsMissingBrandBeforeProductLookup() {
        Brand otherBrand = new Brand("Other Brand");
        Map<Long, Brand> brands = Map.of(20L, otherBrand);
        BrandDeletionService service = new BrandDeletionService(
            lookupRepository(brands), productLookup(id -> {
                throw new AssertionError("없는 브랜드의 상품을 조회하면 안 된다.");
            }));

        assertAll(
            () -> assertDeletionRejected(() -> service.delete(BRAND_ID, DELETED_AT),
                BrandDeletionException.Reason.BRAND_NOT_FOUND),
            () -> assertThat(otherBrand.isDeleted()).isFalse(),
            () -> assertThat(otherBrand.getDeletedAt()).isNull(),
            () -> assertThat(otherBrand.getName()).isEqualTo("Other Brand")
        );
    }

    @DisplayName("BRAND-DELETE-04: 재삭제는 상품을 다시 조회하지 않고 최초 상태를 반환한다.")
    @Test
    void returnsDeletedBrandWithoutLookingUpProductsAgain() {
        Brand brand = new Brand("Original Brand");
        brand.delete(DELETED_AT);
        Map<Long, Brand> brands = Map.of(BRAND_ID, brand);
        BrandDeletionService service = new BrandDeletionService(
            lookupRepository(brands), productLookup(id -> {
                throw new AssertionError("재삭제에서 상품을 다시 조회하면 안 된다.");
            }));

        Brand result = service.delete(BRAND_ID, DELETED_AT.plusDays(1));

        assertAll(
            () -> assertThat(result).isSameAs(brand),
            () -> assertThat(brand.isDeleted()).isTrue(),
            () -> assertThat(brand.getDeletedAt()).isEqualTo(DELETED_AT),
            () -> assertThat(brand.getName()).isEqualTo("Original Brand")
        );
    }

    @DisplayName("BRAND-DELETE-05: 상품 조회가 실패하면 브랜드 상태를 변경하지 않는다.")
    @Test
    void preservesBrandWhenProductLookupFails() {
        Brand brand = new Brand("Original Brand");
        Map<Long, Brand> brands = Map.of(BRAND_ID, brand);
        IllegalStateException lookupFailure = new IllegalStateException("상품 조회 실패");
        BrandDeletionService service = new BrandDeletionService(
            lookupRepository(brands), productLookup(id -> {
                throw lookupFailure;
            }));

        assertAll(
            () -> assertThatThrownBy(() -> service.delete(BRAND_ID, DELETED_AT)).isSameAs(lookupFailure),
            () -> assertThat(brand.isDeleted()).isFalse(),
            () -> assertThat(brand.getDeletedAt()).isNull(),
            () -> assertThat(brand.getName()).isEqualTo("Original Brand")
        );
    }

    @DisplayName("BRAND-DELETE-06: 삭제 대상 브랜드의 상품 존재 여부만 검사한다.")
    @Test
    void checksProductsOfTheRequestedBrandOnly() {
        Brand brand = new Brand("Original Brand");
        Brand otherBrand = new Brand("Other Brand");
        long otherBrandId = 20L;
        Map<Long, Brand> brands = Map.of(BRAND_ID, brand, otherBrandId, otherBrand);
        Set<Long> brandsWithProducts = Set.of(otherBrandId);
        BrandDeletionService service = new BrandDeletionService(
            lookupRepository(brands), productLookup(brandsWithProducts::contains));

        Brand result = service.delete(BRAND_ID, DELETED_AT);

        assertAll(
            () -> assertThat(result).isSameAs(brand),
            () -> assertThat(brand.isDeleted()).isTrue(),
            () -> assertThat(brand.getDeletedAt()).isEqualTo(DELETED_AT),
            () -> assertThat(brand.getName()).isEqualTo("Original Brand"),
            () -> assertDeletionRejected(() -> service.delete(otherBrandId, DELETED_AT),
                BrandDeletionException.Reason.NON_DELETED_PRODUCTS_EXIST),
            () -> assertThat(otherBrand.isDeleted()).isFalse(),
            () -> assertThat(otherBrand.getDeletedAt()).isNull(),
            () -> assertThat(otherBrand.getName()).isEqualTo("Other Brand")
        );
    }

    @DisplayName("BRAND-DELETE-07: 미삭제 상품이 없어지면 거절됐던 브랜드도 다시 삭제할 수 있다.")
    @Test
    void deletesAfterNonDeletedProductsAreRemoved() {
        Brand brand = new Brand("Original Brand");
        Map<Long, Brand> brands = Map.of(BRAND_ID, brand);
        AtomicBoolean productsExist = new AtomicBoolean(true);
        BrandDeletionService service = new BrandDeletionService(
            lookupRepository(brands), productLookup(id -> productsExist.get()));
        assertDeletionRejected(() -> service.delete(BRAND_ID, DELETED_AT),
            BrandDeletionException.Reason.NON_DELETED_PRODUCTS_EXIST);
        productsExist.set(false);
        ZonedDateTime successfulDeletionTime = DELETED_AT.plusDays(1);

        Brand result = service.delete(BRAND_ID, successfulDeletionTime);

        assertAll(
            () -> assertThat(result).isSameAs(brand),
            () -> assertThat(brand.isDeleted()).isTrue(),
            () -> assertThat(brand.getDeletedAt()).isEqualTo(successfulDeletionTime),
            () -> assertThat(brand.getName()).isEqualTo("Original Brand")
        );
    }

    private BrandRepository lookupRepository(Map<Long, Brand> brands) {
        return new BrandRepository() {
            @Override
            public Brand save(Brand brand) {
                throw new AssertionError("삭제 조건 서비스가 저장하면 안 된다.");
            }

            @Override
            public Optional<Brand> lockById(long brandId) {
                return Optional.ofNullable(brands.get(brandId));
            }

            @Override
            public Optional<Brand> findById(long brandId) {
                return Optional.ofNullable(brands.get(brandId));
            }
        };
    }

    private ProductRepository productLookup(LongPredicate exists) {
        return new ProductRepository() {
            @Override
            public boolean existsNonDeletedByBrandId(long brandId) {
                return exists.test(brandId);
            }

            @Override
            public Product save(Product product) {
                throw new AssertionError("삭제 조건에서 상품을 저장하면 안 된다.");
            }

            @Override
            public Optional<Product> findById(long productId) {
                throw new AssertionError("삭제 조건에서 상품을 상세 조회하면 안 된다.");
            }

            @Override
            public Optional<Product> lockById(long productId) {
                throw new AssertionError("삭제 조건에서 상품을 잠그면 안 된다.");
            }

            @Override
            public Optional<Long> findBrandId(long productId) {
                throw new AssertionError("삭제 조건에서 상품의 브랜드를 찾으면 안 된다.");
            }
        };
    }

    private void assertDeletionRejected(ThrowingCallable action, BrandDeletionException.Reason reason) {
        assertThatThrownBy(action)
            .isInstanceOfSatisfying(BrandDeletionException.class,
                error -> assertThat(error.getReason()).isEqualTo(reason));
    }
}
