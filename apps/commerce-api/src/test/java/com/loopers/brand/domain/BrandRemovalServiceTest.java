package com.loopers.brand.domain;

import com.loopers.product.domain.Product;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

class BrandRemovalServiceTest {

    private final BrandRemovalService service = new BrandRemovalService();

    @DisplayName("[INV-BRAND-03] 삭제된 브랜드에 속한 상품은 모두 삭제된 상태다.")
    @Nested
    class DeletedBrandHasOnlyDeletedProducts {

        @DisplayName("[상태 전이] 활성 브랜드를 삭제하면 연결된 미삭제 상품이 모두 삭제된다.")
        @Test
        void removesBrandAndAllActiveProducts() {
            Brand brand = new Brand("Nike");
            Product first = new Product(brand.getId(), "Air", 3_000L);
            Product second = new Product(brand.getId(), "Dunk", 2_000L);

            service.remove(brand, List.of(first, second));

            assertAll(
                () -> assertThat(brand.isDeleted()).isTrue(),
                () -> assertThat(first.isDeleted()).isTrue(),
                () -> assertThat(second.isDeleted()).isTrue()
            );
        }

        @DisplayName("[상태 전이] 이미 삭제된 연결 상품은 그대로 두고 남은 활성 상품과 브랜드를 삭제한다.")
        @Test
        void removesOnlyRemainingActiveProducts() {
            Brand brand = new Brand("Nike");
            Product deleted = new Product(brand.getId(), "Air", 3_000L);
            deleted.delete();
            var deletedAt = deleted.getDeletedAt();
            Product active = new Product(brand.getId(), "Dunk", 2_000L);

            service.remove(brand, List.of(deleted, active));

            assertAll(
                () -> assertThat(brand.isDeleted()).isTrue(),
                () -> assertThat(deleted.isDeleted()).isTrue(),
                () -> assertThat(deleted.getDeletedAt()).isEqualTo(deletedAt),
                () -> assertThat(active.isDeleted()).isTrue()
            );
        }
    }
}
