package com.loopers.infrastructure.brand;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest
class BrandRepositoryIntegrationTest {

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("BRAND-PERSIST-01: 새 브랜드를 저장하면 ID와 감사 시각이 생기고 MySQL에 이름을 기록한다.")
    @Test
    void persistsNewBrand() {
        Brand saved = saveNewBrand("  루퍼스 Brand  ");

        assertThat(saved).isNotNull();
        assertThat(saved.getId()).isPositive();
        assertAll(
            () -> assertThat(saved.getCreatedAt()).isNotNull(),
            () -> assertThat(saved.getUpdatedAt()).isEqualTo(saved.getCreatedAt()),
            () -> assertThat(saved.isDeleted()).isFalse(),
            () -> assertThat(jdbcTemplate.queryForObject(
                "SELECT name FROM brand WHERE id = ?", String.class, saved.getId()))
                .isEqualTo("루퍼스 Brand")
        );
    }

    @DisplayName("BRAND-PERSIST-02: 커밋한 브랜드를 다른 트랜잭션에서 같은 ID와 상태로 다시 읽는다.")
    @Test
    void reloadsBrandInAnotherTransaction() {
        Brand saved = saveNewBrand("루퍼스 Brand");

        Brand loaded = transaction
            .execute(status -> brandRepository.findById(saved.getId()).orElseThrow());

        assertThat(loaded).isNotNull();
        assertAll(
            () -> assertThat(loaded).isNotSameAs(saved),
            () -> assertThat(loaded.getId()).isEqualTo(saved.getId()),
            () -> assertThat(loaded.getName()).isEqualTo("루퍼스 Brand"),
            () -> assertThat(loaded.isDeleted()).isFalse(),
            () -> assertThat(loaded.getDeletedAt()).isNull(),
            () -> assertThat(loaded.getCreatedAt()).isNotNull(),
            () -> assertThat(loaded.getUpdatedAt()).isEqualTo(loaded.getCreatedAt())
        );
    }

    @DisplayName("BRAND-PERSIST-03: DB에 없는 ID는 빈 결과를 반환한다.")
    @Test
    void returnsEmptyForMissingId() {
        var result = transaction
            .execute(status -> brandRepository.findById(Long.MAX_VALUE));

        assertThat(result).isEmpty();
    }

    @DisplayName("BRAND-PERSIST-04: 한글·이모지 100개 이름을 손실 없이 저장하고 다시 읽는다.")
    @ParameterizedTest
    @ValueSource(strings = {"한", "😀"})
    void roundTripsOneHundredCodePoints(String character) {
        String name = character.repeat(100);
        Brand saved = saveNewBrand("  " + name + "  ");

        Brand loaded = transaction
            .execute(status -> brandRepository.findById(saved.getId()).orElseThrow());

        assertAll(
            () -> assertThat(loaded).isNotSameAs(saved),
            () -> assertThat(loaded.getName()).isEqualTo(name),
            () -> assertThat(jdbcTemplate.queryForObject(
                "SELECT name FROM brand WHERE id = ?", String.class, saved.getId()))
                .isEqualTo(name)
        );
    }

    @DisplayName("BRAND-PERSIST-05: findById는 논리 삭제된 행도 삭제 시각과 함께 반환한다.")
    @Test
    void includesLogicallyDeletedBrand() {
        Brand saved = saveNewBrand("삭제된 브랜드");
        LocalDateTime deletedAtUtc = LocalDateTime.of(2026, 9, 18, 3, 0);
        // 조회 계약 검증용 DB 상태이며 삭제 유스케이스를 실행한 것은 아니다.
        jdbcTemplate.update("UPDATE brand SET deleted_at = ? WHERE id = ?", deletedAtUtc, saved.getId());

        Brand loaded = transaction
            .execute(status -> brandRepository.findById(saved.getId()).orElseThrow());

        assertAll(
            () -> assertThat(loaded.getId()).isEqualTo(saved.getId()),
            () -> assertThat(loaded.getName()).isEqualTo("삭제된 브랜드"),
            () -> assertThat(loaded.isDeleted()).isTrue(),
            () -> assertThat(loaded.getDeletedAt().toInstant()).isEqualTo(deletedAtUtc.toInstant(ZoneOffset.UTC))
        );
    }

    @DisplayName("BRAND-PERSIST-06: 저장 후 바깥 트랜잭션이 실패하면 INSERT도 롤백한다.")
    @Test
    void rollsBackInsertWithCallerTransaction() {
        IllegalStateException failure = new IllegalStateException("저장 후 실패");

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            Brand saved = brandRepository.save(new Brand("롤백할 브랜드"));
            assertThat(saved.getId()).isPositive();
            throw failure;
        })).isSameAs(failure);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM brand", Long.class)).isZero();
    }

    @DisplayName("BRAND-PERSIST-07: 변경 감지로 이름을 저장할 때 생성 시각을 유지하고 수정 시각을 갱신한다.")
    @Test
    void updatesAuditTimestampWhenManagedBrandChanges() {
        Brand saved = saveNewBrand("변경 전");
        LocalDateTime originalTimeUtc = LocalDateTime.of(2000, 1, 1, 0, 0);
        jdbcTemplate.update("UPDATE brand SET created_at = ?, updated_at = ? WHERE id = ?",
            originalTimeUtc, originalTimeUtc, saved.getId());

        transaction.executeWithoutResult(status -> {
            Brand brand = brandRepository.findById(saved.getId()).orElseThrow();
            brand.rename("  변경 후  ");
        });

        Brand loaded = transaction
            .execute(status -> brandRepository.findById(saved.getId()).orElseThrow());
        assertAll(
            () -> assertThat(loaded.getName()).isEqualTo("변경 후"),
            () -> assertThat(loaded.getCreatedAt().toInstant()).isEqualTo(originalTimeUtc.toInstant(ZoneOffset.UTC)),
            () -> assertThat(loaded.getUpdatedAt().toInstant()).isAfter(originalTimeUtc.toInstant(ZoneOffset.UTC))
        );
    }

    private Brand saveNewBrand(String name) {
        return transaction.execute(status -> brandRepository.save(new Brand(name)));
    }
}
