package com.loopers.domain.brand;

import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

class BrandDeletionServiceTest {

    private static final long BRAND_ID = 10L;
    private static final ZonedDateTime DELETED_AT = ZonedDateTime.parse("2026-09-18T12:00:00+09:00");

    @DisplayName("W3-BRAND-DOMAIN-01: 변경할 상품이 없어도 조회한 브랜드를 삭제 상태로 변경한다.")
    @Test
    void deletesBrandWithoutNonDeletedProducts() {
        Brand brand = new Brand("Original Brand");
        Map<Long, Brand> brands = Map.of(BRAND_ID, brand);
        BrandDeletionService service = new BrandDeletionService(
            lookupRepository(brands), productDeletion((id, timestamp) -> 0));

        Brand result = service.delete(BRAND_ID, DELETED_AT);

        assertAll(
            () -> assertThat(result).isSameAs(brand),
            () -> assertThat(brand.isDeleted()).isTrue(),
            () -> assertThat(brand.getDeletedAt()).isEqualTo(DELETED_AT),
            () -> assertThat(brand.getName()).isEqualTo("Original Brand")
        );
    }

    @DisplayName("W3-BRAND-DOMAIN-02: 연결 상품을 같은 삭제 시각으로 변경한 뒤 브랜드를 삭제한다.")
    @Test
    void deletesBrandAfterSoftDeletingNonDeletedProducts() {
        Brand brand = new Brand("Original Brand");
        Map<Long, Brand> brands = Map.of(BRAND_ID, brand);
        AtomicInteger calls = new AtomicInteger();
        // W3는 상품 존재 시 거절하던 W2 계약을 브랜드·상품 일괄 논리 삭제로 대체한다.
        BrandDeletionService service = new BrandDeletionService(
            lookupRepository(brands), productDeletion((id, timestamp) -> {
                calls.incrementAndGet();
                assertThat(id).isEqualTo(BRAND_ID);
                assertThat(timestamp).isEqualTo(DELETED_AT);
                assertThat(brand.isDeleted()).isFalse();
                return 2;
            }));

        Brand result = service.delete(BRAND_ID, DELETED_AT);

        assertAll(
            () -> assertThat(result).isSameAs(brand),
            () -> assertThat(calls.get()).isEqualTo(1),
            () -> assertThat(brand.isDeleted()).isTrue(),
            () -> assertThat(brand.getDeletedAt()).isEqualTo(DELETED_AT),
            () -> assertThat(brand.getName()).isEqualTo("Original Brand")
        );
    }

    @DisplayName("W3-BRAND-DOMAIN-03: 없는 브랜드는 상품 변경 없이 거절하고 다른 브랜드를 유지한다.")
    @Test
    void rejectsMissingBrandBeforeProductDeletion() {
        Brand otherBrand = new Brand("Other Brand");
        Map<Long, Brand> brands = Map.of(20L, otherBrand);
        BrandDeletionService service = new BrandDeletionService(
            lookupRepository(brands), productDeletion((id, timestamp) -> {
                throw new AssertionError("없는 브랜드의 상품을 변경하면 안 된다.");
            }));

        assertAll(
            () -> assertDeletionRejected(() -> service.delete(BRAND_ID, DELETED_AT),
                BrandDeletionException.Reason.BRAND_NOT_FOUND),
            () -> assertThat(otherBrand.isDeleted()).isFalse(),
            () -> assertThat(otherBrand.getDeletedAt()).isNull(),
            () -> assertThat(otherBrand.getName()).isEqualTo("Other Brand")
        );
    }

    @DisplayName("W3-BRAND-DOMAIN-04: 이미 삭제된 브랜드는 상품 변경 없이 최초 상태를 반환한다.")
    @Test
    void returnsDeletedBrandWithoutDeletingProductsAgain() {
        Brand brand = new Brand("Original Brand");
        brand.delete(DELETED_AT);
        Map<Long, Brand> brands = Map.of(BRAND_ID, brand);
        BrandDeletionService service = new BrandDeletionService(
            lookupRepository(brands), productDeletion((id, timestamp) -> {
                throw new AssertionError("재삭제에서 상품을 다시 변경하면 안 된다.");
            }));

        Brand result = service.delete(BRAND_ID, DELETED_AT.plusDays(1));

        assertAll(
            () -> assertThat(result).isSameAs(brand),
            () -> assertThat(brand.isDeleted()).isTrue(),
            () -> assertThat(brand.getDeletedAt()).isEqualTo(DELETED_AT),
            () -> assertThat(brand.getName()).isEqualTo("Original Brand")
        );
    }

    @DisplayName("W3-BRAND-DOMAIN-05: 상품 일괄 변경이 실패하면 브랜드 상태를 변경하지 않는다.")
    @Test
    void preservesBrandWhenProductDeletionFails() {
        Brand brand = new Brand("Original Brand");
        Map<Long, Brand> brands = Map.of(BRAND_ID, brand);
        IllegalStateException deletionFailure = new IllegalStateException("상품 일괄 변경 실패");
        BrandDeletionService service = new BrandDeletionService(
            lookupRepository(brands), productDeletion((id, timestamp) -> {
                throw deletionFailure;
            }));

        assertAll(
            () -> assertThatThrownBy(() -> service.delete(BRAND_ID, DELETED_AT)).isSameAs(deletionFailure),
            () -> assertThat(brand.isDeleted()).isFalse(),
            () -> assertThat(brand.getDeletedAt()).isNull(),
            () -> assertThat(brand.getName()).isEqualTo("Original Brand")
        );
    }

    @DisplayName("W3-BRAND-DOMAIN-06: 요청한 브랜드의 상품 변경만 위임하고 다른 브랜드는 보존한다.")
    @Test
    void delegatesProductDeletionForTheRequestedBrandOnly() {
        Brand brand = new Brand("Original Brand");
        Brand otherBrand = new Brand("Other Brand");
        long otherBrandId = 20L;
        Map<Long, Brand> brands = Map.of(BRAND_ID, brand, otherBrandId, otherBrand);
        List<Long> requestedBrandIds = new ArrayList<>();
        BrandDeletionService service = new BrandDeletionService(
            lookupRepository(brands), productDeletion((id, timestamp) -> {
                requestedBrandIds.add(id);
                return 2;
            }));

        Brand result = service.delete(BRAND_ID, DELETED_AT);

        assertAll(
            () -> assertThat(result).isSameAs(brand),
            () -> assertThat(brand.isDeleted()).isTrue(),
            () -> assertThat(brand.getDeletedAt()).isEqualTo(DELETED_AT),
            () -> assertThat(brand.getName()).isEqualTo("Original Brand"),
            () -> assertThat(requestedBrandIds).containsExactly(BRAND_ID),
            () -> assertThat(otherBrand.isDeleted()).isFalse(),
            () -> assertThat(otherBrand.getDeletedAt()).isNull(),
            () -> assertThat(otherBrand.getName()).isEqualTo("Other Brand")
        );
    }

    @DisplayName("W3-BRAND-DOMAIN-07: 상품 변경 실패 후 다시 요청하면 성공한 요청의 시각으로 삭제한다.")
    @Test
    void deletesOnRetryAfterProductDeletionFailure() {
        Brand brand = new Brand("Original Brand");
        Map<Long, Brand> brands = Map.of(BRAND_ID, brand);
        AtomicBoolean failDeletion = new AtomicBoolean(true);
        List<ZonedDateTime> deletionAttempts = new ArrayList<>();
        IllegalStateException failure = new IllegalStateException("상품 변경 실패");
        BrandDeletionService service = new BrandDeletionService(
            lookupRepository(brands), productDeletion((id, timestamp) -> {
                deletionAttempts.add(timestamp);
                if (failDeletion.get()) {
                    throw failure;
                }
                return 2;
            }));
        assertThatThrownBy(() -> service.delete(BRAND_ID, DELETED_AT)).isSameAs(failure);
        assertThat(brand.isDeleted()).isFalse();
        assertThat(brand.getDeletedAt()).isNull();
        assertThat(brand.getName()).isEqualTo("Original Brand");
        failDeletion.set(false);
        ZonedDateTime successfulDeletionTime = DELETED_AT.plusDays(1);

        Brand result = service.delete(BRAND_ID, successfulDeletionTime);

        assertAll(
            () -> assertThat(result).isSameAs(brand),
            () -> assertThat(brand.isDeleted()).isTrue(),
            () -> assertThat(brand.getDeletedAt()).isEqualTo(successfulDeletionTime),
            () -> assertThat(deletionAttempts).containsExactly(DELETED_AT, successfulDeletionTime),
            () -> assertThat(brand.getName()).isEqualTo("Original Brand")
        );
    }

    @DisplayName("W3-BRAND-DOMAIN-08: 성공 후 재삭제는 상품을 다시 변경하지 않고 최초 삭제 시각을 보존한다.")
    @Test
    void repeatedDeletionPreservesTheFirstSuccessfulResult() {
        Brand brand = new Brand("Original Brand");
        AtomicInteger calls = new AtomicInteger();
        BrandDeletionService service = new BrandDeletionService(
            lookupRepository(Map.of(BRAND_ID, brand)), productDeletion((id, timestamp) -> {
                calls.incrementAndGet();
                return 2;
            }));
        service.delete(BRAND_ID, DELETED_AT);

        Brand repeated = service.delete(BRAND_ID, DELETED_AT.plusDays(1));

        assertAll(
            () -> assertThat(repeated).isSameAs(brand),
            () -> assertThat(calls.get()).isEqualTo(1),
            () -> assertThat(brand.isDeleted()).isTrue(),
            () -> assertThat(brand.getDeletedAt()).isEqualTo(DELETED_AT),
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

    private ProductRepository productDeletion(BiFunction<Long, ZonedDateTime, Integer> deletion) {
        return new ProductRepository() {
            @Override
            public int softDeleteNonDeletedByBrandId(long brandId, ZonedDateTime deletedAt) {
                return deletion.apply(brandId, deletedAt);
            }

            @Override
            public boolean existsNonDeletedByBrandId(long brandId) {
                throw new AssertionError("W3 브랜드 삭제에서는 상품 존재를 거절 조건으로 조회하면 안 된다.");
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
