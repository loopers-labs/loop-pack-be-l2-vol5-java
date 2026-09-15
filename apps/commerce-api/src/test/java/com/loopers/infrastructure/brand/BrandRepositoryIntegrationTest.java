package com.loopers.infrastructure.brand;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.brand.BrandRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class BrandRepositoryIntegrationTest {

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private EntityManager entityManager;

    @DisplayName("저장한 브랜드를 영속성 컨텍스트를 비운 뒤 다시 읽으면 이름·설명·생성 시각이 그대로다.")
    @Test
    void savesAndReloadsBrand() {
        // arrange
        BrandModel saved = brandRepository.save(new BrandModel("나이키", "스포츠 브랜드"));
        entityManager.flush();
        entityManager.clear();

        // act
        Optional<BrandModel> found = brandRepository.findActiveById(saved.getId());

        // assert
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("나이키");
        assertThat(found.get().getDescription()).isEqualTo("스포츠 브랜드");
        assertThat(found.get().getCreatedAt()).isNotNull();
    }

    @DisplayName("BRD-03 삭제된 브랜드는 상세 조회와 목록에서 제외된다.")
    @Test
    void excludesDeletedBrands() {
        // arrange
        BrandModel alive = brandRepository.save(new BrandModel("나이키", null));
        BrandModel deleted = brandRepository.save(new BrandModel("아디다스", null));
        deleted.delete();
        entityManager.flush();
        entityManager.clear();

        // act
        Optional<BrandModel> foundDeleted = brandRepository.findActiveById(deleted.getId());
        Page<BrandModel> page = brandRepository.findActive(PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "id")));

        // assert
        assertThat(foundDeleted).isEmpty();
        assertThat(page.getContent()).extracting(BrandModel::getId).containsExactly(alive.getId());
        assertThat(page.getTotalElements()).isEqualTo(1);
    }
}
