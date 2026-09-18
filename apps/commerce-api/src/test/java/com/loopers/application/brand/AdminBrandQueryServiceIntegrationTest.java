package com.loopers.application.brand;

import com.loopers.application.user.UserResolutionException;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.user.UserRole;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest
class AdminBrandQueryServiceIntegrationTest {

    @Autowired
    private AdminBrandQueryService adminBrandQueryService;

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

    @DisplayName("ADMIN-BRAND-QUERY-01: 관리자가 요청한 활성 브랜드의 정보·감사 시각을 반환하고 DB 상태를 보존한다.")
    @Test
    void returnsRequestedActiveBrandWithoutChangingStoredState() {
        brandRepository.save(new Brand("다른 브랜드"));
        Brand target = brandRepository.save(new Brand("  조회할 브랜드  "));
        Brand stored = brandRepository.findById(target.getId()).orElseThrow();
        var before = storedBrands();

        AdminBrandInfo result = adminBrandQueryService.getDetail(UserRole.ADMIN, target.getId());

        assertThat(result).isNotNull();
        assertAll(
            () -> assertThat(result.brandId()).isEqualTo(target.getId()),
            () -> assertThat(result.name()).isEqualTo("조회할 브랜드"),
            () -> assertThat(result.createdAt()).isEqualTo(stored.getCreatedAt()),
            () -> assertThat(result.updatedAt()).isEqualTo(stored.getUpdatedAt()),
            () -> assertThat(result.deletedAt()).isNull(),
            () -> assertThat(storedBrands()).isEqualTo(before)
        );
    }

    @DisplayName("ADMIN-BRAND-QUERY-02: 관리자의 없는 브랜드 조회는 BRAND_NOT_FOUND로 거절하고 다른 행을 보존한다.")
    @Test
    void rejectsMissingBrandWithoutChangingStoredState() {
        brandRepository.save(new Brand("기존 브랜드"));
        var before = storedBrands();

        assertAll(
            () -> assertThatThrownBy(() -> adminBrandQueryService.getDetail(UserRole.ADMIN, Long.MAX_VALUE))
                .isInstanceOfSatisfying(BrandQueryException.class,
                    error -> assertThat(error.getReason()).isEqualTo(BrandQueryException.Reason.BRAND_NOT_FOUND)),
            () -> assertThat(storedBrands()).isEqualTo(before)
        );
    }

    @DisplayName("ADMIN-BRAND-QUERY-03: 관리자는 삭제 브랜드도 삭제·감사 시각과 함께 조회하고 DB 상태를 보존한다.")
    @Test
    void includesDeletedBrandWithoutChangingStoredState() {
        brandRepository.save(new Brand("활성 브랜드"));
        Brand deleted = brandRepository.save(new Brand("삭제된 브랜드"));
        LocalDateTime deletedAtUtc = LocalDateTime.of(2026, 9, 18, 3, 0);
        // 관리자 조회의 삭제 상태 fixture이며 삭제 유스케이스를 실행한 것은 아니다.
        assertThat(jdbcTemplate.update("UPDATE brand SET deleted_at = ? WHERE id = ?",
            deletedAtUtc, deleted.getId())).isEqualTo(1);
        Brand stored = brandRepository.findById(deleted.getId()).orElseThrow();
        var before = storedBrands();

        AdminBrandInfo result = adminBrandQueryService.getDetail(UserRole.ADMIN, deleted.getId());

        assertAll(
            () -> assertThat(result.brandId()).isEqualTo(deleted.getId()),
            () -> assertThat(result.name()).isEqualTo("삭제된 브랜드"),
            () -> assertThat(result.createdAt()).isEqualTo(stored.getCreatedAt()),
            () -> assertThat(result.updatedAt()).isEqualTo(stored.getUpdatedAt()),
            () -> assertThat(result.deletedAt().toInstant()).isEqualTo(deletedAtUtc.toInstant(ZoneOffset.UTC)),
            () -> assertThat(storedBrands()).isEqualTo(before)
        );
    }

    @DisplayName("ADMIN-BRAND-QUERY-04: 일반 고객은 대상 존재 여부보다 관리자 권한 오류가 먼저이며 DB 상태를 보존한다.")
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsCustomerBeforeCheckingBrandExistence(boolean missingBrand) {
        Brand target = brandRepository.save(new Brand("기존 브랜드"));
        long brandId = missingBrand ? Long.MAX_VALUE : target.getId();

        assertUserResolutionFailure(UserRole.CUSTOMER, brandId, UserResolutionException.Reason.ADMIN_REQUIRED);
    }

    @DisplayName("ADMIN-BRAND-QUERY-05: 관리자 역할 누락은 권한 오류로 거절하고 브랜드 상태를 보존한다.")
    @Test
    void rejectsMissingRequesterWithoutChangingStoredState() {
        Brand target = brandRepository.save(new Brand("기존 브랜드"));

        assertUserResolutionFailure(null, target.getId(), UserResolutionException.Reason.ADMIN_REQUIRED);
    }

    @DisplayName("ADMIN-BRAND-QUERY-06: 미식별 역할은 없는 브랜드보다 관리자 권한 오류가 먼저이며 DB 상태를 보존한다.")
    @Test
    void rejectsMissingRoleBeforeCheckingBrandExistence() {
        brandRepository.save(new Brand("기존 브랜드"));

        assertUserResolutionFailure(null, Long.MAX_VALUE, UserResolutionException.Reason.ADMIN_REQUIRED);
    }

    private void assertUserResolutionFailure(UserRole requester, long brandId, UserResolutionException.Reason reason) {
        var before = storedBrands();

        assertAll(
            () -> assertThatThrownBy(() -> adminBrandQueryService.getDetail(requester, brandId))
                .isInstanceOfSatisfying(UserResolutionException.class,
                    error -> assertThat(error.getReason()).isEqualTo(reason)),
            () -> assertThat(storedBrands()).isEqualTo(before)
        );
    }

    private List<Map<String, Object>> storedBrands() {
        return jdbcTemplate.queryForList(
            "SELECT id, name, created_at, updated_at, deleted_at FROM brand ORDER BY id");
    }
}
