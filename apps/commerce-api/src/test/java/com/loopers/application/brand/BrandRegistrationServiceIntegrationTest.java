package com.loopers.application.brand;

import com.loopers.application.user.UserResolutionException;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandNameException;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.user.UserRole;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest
class BrandRegistrationServiceIntegrationTest {

    @Autowired
    private BrandRegistrationService registrationService;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("BRAND-REGISTER-01: 관리자가 등록하면 정리된 이름과 새 ID를 저장하고 반환한다.")
    @Test
    void registersBrandForAdmin() {
        AdminBrandInfo result = registrationService.register(UserRole.ADMIN, "  새 브랜드  ");

        assertThat(result).isNotNull();
        var stored = brandRepository.findById(result.brandId()).orElseThrow();
        assertAll(
            () -> assertThat(result.brandId()).isPositive(),
            () -> assertThat(result.name()).isEqualTo("새 브랜드"),
            () -> assertThat(result.createdAt()).isNotNull(),
            () -> assertThat(result.updatedAt()).isEqualTo(result.createdAt()),
            () -> assertThat(result.deletedAt()).isNull(),
            () -> assertThat(stored.getName()).isEqualTo("새 브랜드"),
            () -> assertThat(stored.isDeleted()).isFalse()
        );
    }

    @DisplayName("BRAND-REGISTER-02: 고객의 등록은 이름 오류보다 권한 오류를 먼저 반환하고 저장하지 않는다.")
    @ParameterizedTest
    @ValueSource(strings = {"정상 이름", " "})
    void rejectsCustomerBeforeValidatingName(String name) {
        assertAll(
            () -> assertThatThrownBy(() -> registrationService.register(UserRole.CUSTOMER, name))
                .isInstanceOfSatisfying(UserResolutionException.class,
                    error -> assertThat(error.getReason()).isEqualTo(UserResolutionException.Reason.ADMIN_REQUIRED)),
            () -> assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM brand", Long.class)).isZero()
        );
    }

    @DisplayName("BRAND-REGISTER-03: 관리자의 잘못된 이름은 저장하지 않고 기존 행도 보존한다.")
    @ParameterizedTest
    @MethodSource("invalidNames")
    void rejectsInvalidNameWithoutSaving(String name, BrandNameException.Reason reason) {
        brandRepository.save(new Brand("기존 브랜드"));
        var before = jdbcTemplate.queryForList("SELECT * FROM brand ORDER BY id");

        assertAll(
            () -> assertThatThrownBy(() -> registrationService.register(UserRole.ADMIN, name))
                .isInstanceOfSatisfying(BrandNameException.class,
                    error -> assertThat(error.getReason()).isEqualTo(reason)),
            () -> assertThat(jdbcTemplate.queryForList("SELECT * FROM brand ORDER BY id")).isEqualTo(before)
        );
    }

    @DisplayName("BRAND-REGISTER-04: 같은 이름으로 다시 등록해도 별개의 브랜드 ID를 발급한다.")
    @Test
    void registersSeparateBrandsWithTheSameName() {
        AdminBrandInfo first = registrationService.register(UserRole.ADMIN, "같은 이름");
        AdminBrandInfo second = registrationService.register(UserRole.ADMIN, "같은 이름");

        assertAll(
            () -> assertThat(second.brandId()).isNotEqualTo(first.brandId()),
            () -> assertThat(second.name()).isEqualTo(first.name()),
            () -> assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM brand", Long.class)).isEqualTo(2L)
        );
    }

    private static Stream<Arguments> invalidNames() {
        return Stream.of(
            Arguments.of(null, BrandNameException.Reason.EMPTY_NAME),
            Arguments.of(" \t ", BrandNameException.Reason.EMPTY_NAME),
            Arguments.of("한".repeat(101), BrandNameException.Reason.NAME_TOO_LONG)
        );
    }
}
