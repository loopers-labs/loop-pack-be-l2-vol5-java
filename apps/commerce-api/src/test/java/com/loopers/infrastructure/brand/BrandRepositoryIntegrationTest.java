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

    /**
     * 11장 JPA 구현 메모: BaseEntity.id가 0L로 초기화돼 Spring Data가 새 엔티티로 보지 않고 merge한다.
     * 그래서 저장 후에는 반드시 반환값을 써야 한다는 가정을 고정해 둔다.
     */
    @DisplayName("저장하면 반환된 엔티티에 id가 채워지고, 인자로 넘긴 객체의 id는 0으로 남는다.")
    @Test
    void saveReturnsEntityWithGeneratedId() {
        // arrange
        BrandModel argument = new BrandModel("나이키", null);

        // act
        BrandModel saved = brandRepository.save(argument);

        // assert
        assertThat(saved.getId()).isPositive();
        assertThat(argument.getId()).isZero();
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
