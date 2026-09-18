package com.loopers.application.brand;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest
class BrandQueryServiceIntegrationTest {

    @Autowired
    private BrandQueryService brandQueryService;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("BRAND-QUERY-01: 요청한 미삭제 브랜드의 ID·이름을 반환하고 저장 상태를 유지한다.")
    @Test
    void returnsRequestedActiveBrandWithoutChangingStoredState() {
        brandRepository.save(new Brand("다른 브랜드"));
        Brand target = brandRepository.save(new Brand("  조회할 브랜드  "));
        var before = storedBrands();

        BrandInfo result = brandQueryService.getDetail(target.getId());

        assertAll(
            () -> assertThat(result).isEqualTo(new BrandInfo(target.getId(), "조회할 브랜드")),
            () -> assertThat(storedBrands())
                .isEqualTo(before)
        );
    }

    @DisplayName("BRAND-QUERY-02: 없는 브랜드는 BRAND_NOT_FOUND로 거절하고 다른 행을 보존한다.")
    @Test
    void rejectsMissingBrandWithoutChangingStoredState() {
        brandRepository.save(new Brand("기존 브랜드"));
        var before = storedBrands();

        assertAll(
            () -> assertBrandNotFound(Long.MAX_VALUE),
            () -> assertThat(storedBrands())
                .isEqualTo(before)
        );
    }

    @DisplayName("BRAND-QUERY-03: 삭제된 브랜드도 BRAND_NOT_FOUND로 거절하고 삭제·감사 시각을 보존한다.")
    @Test
    void rejectsDeletedBrandWithoutChangingStoredState() {
        brandRepository.save(new Brand("노출 가능한 브랜드"));
        Brand deleted = brandRepository.save(new Brand("삭제된 브랜드"));
        LocalDateTime deletedAtUtc = LocalDateTime.of(2026, 9, 18, 3, 0);
        // 고객 조회의 삭제 상태 fixture이며 삭제 유스케이스를 실행한 것은 아니다.
        assertThat(jdbcTemplate.update("UPDATE brand SET deleted_at = ? WHERE id = ?",
            deletedAtUtc, deleted.getId())).isEqualTo(1);
        var before = storedBrands();

        assertAll(
            () -> assertBrandNotFound(deleted.getId()),
            () -> assertThat(storedBrands())
                .isEqualTo(before)
        );
    }

    private List<Map<String, Object>> storedBrands() {
        return jdbcTemplate.queryForList(
            "SELECT id, name, created_at, updated_at, deleted_at FROM brand ORDER BY id");
    }

    private void assertBrandNotFound(long brandId) {
        assertThatThrownBy(() -> brandQueryService.getDetail(brandId))
            .isInstanceOfSatisfying(BrandQueryException.class,
                error -> assertThat(error.getReason()).isEqualTo(BrandQueryException.Reason.BRAND_NOT_FOUND));
    }
}
