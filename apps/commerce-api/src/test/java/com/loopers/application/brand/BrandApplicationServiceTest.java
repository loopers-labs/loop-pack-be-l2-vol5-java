package com.loopers.application.brand;

import com.loopers.application.brand.port.BrandRepository;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrandApplicationServiceTest {
    private final FakeBrandRepository repository = new FakeBrandRepository();
    private final BrandApplicationService service = new BrandApplicationService(repository, org.mockito.Mockito.mock(com.loopers.application.product.port.ProductRepository.class));

    @Test
    @DisplayName("브랜드 생성 시 저장소가 부여한 ID와 저장된 이름을 반환한다")
    void createsAndSavesBrand() {
        BrandResult result = service.create("브랜드");

        assertThat(result).isEqualTo(new BrandResult(new BrandId(1L), "브랜드"));
        Brand stored = repository.findById(result.id()).orElseThrow();
        assertThat(stored.getName()).isEqualTo("브랜드");
        assertThat(stored.isDeleted()).isFalse();
        assertThat(repository.saveCalls).isEqualTo(1);
    }

    @Test
    @DisplayName("잘못된 이름으로 생성하면 저장하지 않고 기존 브랜드를 유지한다")
    void rejectsInvalidNameBeforeSave() {
        Brand existing = repository.save(Brand.create("기존 브랜드"));
        assertThatThrownBy(() -> service.create(" "))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.saveCalls).isEqualTo(1);
        assertThat(repository.findById(existing.getId())).containsSame(existing);
    }

    @Test
    @DisplayName("활성 브랜드를 조회하면 저장된 ID와 이름을 반환하고 저장하지 않는다")
    void getsActiveBrand() {
        Brand stored = repository.save(Brand.create("브랜드"));
        assertThat(service.getBrand(stored.getId()))
            .isEqualTo(new BrandResult(stored.getId(), "브랜드"));
        assertThat(repository.saveCalls).isEqualTo(1);
    }

    @Test
    @DisplayName("없는 브랜드를 조회하면 없는 대상 오류로 거절한다")
    void rejectsMissingBrand() {
        assertThatThrownBy(() -> service.getBrand(new BrandId(99L)))
            .isInstanceOf(BrandNotFoundException.class);
        assertThat(repository.saveCalls).isZero();
    }

    @Test
    @DisplayName("삭제된 브랜드를 조회하면 없는 대상 오류로 거절하고 삭제 상태를 유지한다")
    void rejectsDeletedBrand() {
        Brand stored = repository.save(Brand.restore(new BrandId(7L), "삭제 브랜드", true));
        assertThatThrownBy(() -> service.getBrand(stored.getId()))
            .isInstanceOf(BrandNotFoundException.class);
        assertThat(repository.findById(stored.getId()).orElseThrow().isDeleted()).isTrue();
        assertThat(repository.saveCalls).isEqualTo(1);
    }

    private static class FakeBrandRepository implements BrandRepository {
        private final Map<BrandId, Brand> brands = new HashMap<>();
        private long sequence;
        private int saveCalls;

        @Override
        public java.util.List<Brand> findPage(int page, int size) {
            return brands.values().stream().sorted(java.util.Comparator.comparingLong((Brand b) -> b.getId().value()).reversed())
                .skip((long) page * size).limit(size).toList();
        }

        @Override
        public java.util.List<Brand> findAllByIds(java.util.Collection<BrandId> ids) {
            return ids.stream().map(brands::get).filter(java.util.Objects::nonNull).toList();
        }

        @Override
        public Optional<Brand> findByIdForUpdate(BrandId id) { return findById(id); }

        @Override
        public Brand save(Brand brand) {
            saveCalls++;
            BrandId id = brand.getId() == null ? new BrandId(++sequence) : brand.getId();
            Brand stored = Brand.restore(id, brand.getName(), brand.isDeleted());
            brands.put(id, stored);
            return stored;
        }

        @Override
        public Optional<Brand> findById(BrandId id) {
            return Optional.ofNullable(brands.get(id));
        }
    }
}
