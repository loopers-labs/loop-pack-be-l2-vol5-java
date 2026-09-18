package com.loopers.infrastructure.brand;

import com.loopers.domain.brand.Brand;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.datasource.init.ScriptStatementFailedException;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

@Testcontainers
class BrandSchemaIntegrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("brand_schema_test");

    private DataSource dataSource;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void applySchemaToEmptyDatabase() {
        dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbcTemplate = new JdbcTemplate(dataSource);
        // 이 클래스 전용 임시 컨테이너만 정리한다. 일반 Spring 통합 테스트 DB는 사용하지 않는다.
        jdbcTemplate.execute("DROP TABLE IF EXISTS brand");
        applySchema();
    }

    @DisplayName("BRAND-SCHEMA-01: 운영 SQL만 적용한 빈 DB에서도 기존 Brand JPA로 저장·조회한다.")
    @Test
    void persistsAndReadsBrandWithoutHibernateSchemaCreation() {
        try (SessionFactory sessionFactory = validatedSessionFactory()) {
            Brand brand = persistBrand(sessionFactory, "  운영 스키마 브랜드  ");

            try (Session session = sessionFactory.openSession()) {
                Brand loaded = session.find(Brand.class, brand.getId());
                assertThat(loaded.getId()).isPositive();
                assertThat(loaded.getName()).isEqualTo("운영 스키마 브랜드");
                assertThat(loaded.getCreatedAt()).isNotNull();
                assertThat(loaded.getUpdatedAt()).isEqualTo(loaded.getCreatedAt());
                assertThat(loaded.isDeleted()).isFalse();
            }
        }
    }

    @DisplayName("BRAND-SCHEMA-02: 한글·이모지 100 코드 포인트를 운영 SQL의 문자셋·컬럼에서 왕복한다.")
    @ParameterizedTest
    @ValueSource(strings = {"한", "😀"})
    void roundTripsMaximumLengthUnicodeName(String character) {
        String name = character.repeat(100);
        try (SessionFactory sessionFactory = validatedSessionFactory()) {
            Brand brand = persistBrand(sessionFactory, name);
            try (Session session = sessionFactory.openSession()) {
                assertThat(session.find(Brand.class, brand.getId()).getName()).isEqualTo(name);
            }
            assertThat(jdbcTemplate.queryForObject("SELECT CHAR_LENGTH(name) FROM brand WHERE id = ?",
                Integer.class, brand.getId())).isEqualTo(100);
        }
    }

    @DisplayName("BRAND-SCHEMA-03: 실제 엔진·문자셋·길이·시각 정밀도·기본 키를 확인한다.")
    @Test
    void definesSchemaAttributesNotCoveredByJpaValidation() {
        assertThat(jdbcTemplate.queryForObject("""
            SELECT ENGINE FROM information_schema.TABLES
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'brand'
            """, String.class)).isEqualTo("InnoDB");
        assertThat(jdbcTemplate.queryForObject("""
            SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'brand' AND COLUMN_NAME = 'name'
            """, Long.class)).isEqualTo(100L);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COLLATION_NAME FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'brand' AND COLUMN_NAME = 'name'
            """, String.class)).isEqualTo("utf8mb4_general_ci");
        assertThat(jdbcTemplate.queryForList("""
            SELECT DATETIME_PRECISION FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'brand'
                AND COLUMN_NAME IN ('created_at', 'updated_at', 'deleted_at')
            """, Integer.class)).containsExactly(6, 6, 6);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COLUMN_KEY FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'brand' AND COLUMN_NAME = 'id'
            """, String.class)).isEqualTo("PRI");
    }

    @DisplayName("BRAND-SCHEMA-04: JPA는 시각을 UTC로 저장하고 마이크로초를 보존하여 복원한다.")
    @Test
    void storesUtcAndRestoresMicrosecondTimestamps() {
        try (SessionFactory sessionFactory = validatedSessionFactory()) {
            Brand brand = persistBrand(sessionFactory, "시각 검증 브랜드");
            LocalDateTime createdAtInDatabase = jdbcTemplate.queryForObject(
                "SELECT created_at FROM brand WHERE id = ?",
                (resultSet, rowNumber) -> resultSet.getObject(1, LocalDateTime.class), brand.getId());
            assertThat(createdAtInDatabase.toInstant(ZoneOffset.UTC))
                .isCloseTo(brand.getCreatedAt().toInstant(), within(1, ChronoUnit.MICROS));

            assertThat(jdbcTemplate.update("""
                UPDATE brand SET created_at = ?, updated_at = ?, deleted_at = ? WHERE id = ?
                """, "2026-09-17 01:02:03.123456", "2026-09-18 04:05:06.234567",
                "2026-09-19 07:08:09.345678", brand.getId())).isEqualTo(1);
            try (Session session = sessionFactory.openSession()) {
                Brand loaded = session.find(Brand.class, brand.getId());
                assertThat(loaded.getCreatedAt().toInstant()).isEqualTo(Instant.parse("2026-09-17T01:02:03.123456Z"));
                assertThat(loaded.getUpdatedAt().toInstant()).isEqualTo(Instant.parse("2026-09-18T04:05:06.234567Z"));
                assertThat(loaded.getDeletedAt().toInstant()).isEqualTo(Instant.parse("2026-09-19T07:08:09.345678Z"));
            }
        }
    }

    @DisplayName("BRAND-SCHEMA-05: 필수 이름·생성·수정 시각이 null인 행은 DB에서도 저장하지 않는다.")
    @ParameterizedTest
    @ValueSource(strings = {"name", "created_at", "updated_at"})
    void rejectsMissingRequiredColumn(String column) {
        String[] values = {"브랜드", "2026-09-18 00:00:00", "2026-09-18 00:00:00"};
        values[List.of("name", "created_at", "updated_at").indexOf(column)] = null;

        assertThatThrownBy(() -> jdbcTemplate.update(
            "INSERT INTO brand (name, created_at, updated_at) VALUES (?, ?, ?)", (Object[]) values))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM brand", Long.class)).isZero();
    }

    @DisplayName("BRAND-SCHEMA-06: 101 코드 포인트를 컬럼에서 잘라 저장하지 않고 거절한다.")
    @Test
    void rejectsNameExceedingColumnLength() {
        assertThatThrownBy(() -> insertRawBrand("😀".repeat(101)))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM brand", Long.class)).isZero();
    }

    @DisplayName("BRAND-SCHEMA-07: 이름에는 유일 제약을 추가하지 않으며 다른 ID로 두 행을 저장한다.")
    @Test
    void permitsRepeatedNamesWithSeparateIds() {
        for (int count = 0; count < 2; count++) {
            insertRawBrand("같은 이름");
        }

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(DISTINCT id) FROM brand", Long.class)).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForList("SELECT name FROM brand", String.class))
            .containsExactly("같은 이름", "같은 이름");
    }

    @DisplayName("BRAND-SCHEMA-08: 이미 적용한 생성 SQL을 재실행하면 실패하고 기존 행을 보존한다.")
    @Test
    void rejectsReapplicationWithoutReplacingExistingRows() {
        insertRawBrand("보존할 브랜드");
        var before = jdbcTemplate.queryForList("SELECT * FROM brand ORDER BY id");

        assertThatThrownBy(this::applySchema)
            .isInstanceOf(ScriptStatementFailedException.class);
        assertThat(jdbcTemplate.queryForList("SELECT * FROM brand ORDER BY id")).isEqualTo(before);
    }

    private void applySchema() {
        new ResourceDatabasePopulator(new ClassPathResource("db/schema/001-brand.sql")).execute(dataSource);
    }

    private Brand persistBrand(SessionFactory sessionFactory, String name) {
        Brand brand = new Brand(name);
        try (Session session = sessionFactory.openSession()) {
            var transaction = session.beginTransaction();
            session.persist(brand);
            transaction.commit();
        }
        return brand;
    }

    private void insertRawBrand(String name) {
        jdbcTemplate.update("""
            INSERT INTO brand (name, created_at, updated_at) VALUES (?, ?, ?)
            """, name, "2026-09-18 00:00:00", "2026-09-18 00:00:00");
    }

    private SessionFactory validatedSessionFactory() {
        Configuration configuration = new Configuration();
        configuration.addAnnotatedClass(Brand.class);
        configuration.addAttributeConverter(BrandNameConverter.class, true);
        configuration.getProperties().put("hibernate.connection.datasource", dataSource);
        configuration.setProperty("hibernate.hbm2ddl.auto", "validate");
        configuration.setProperty("hibernate.timezone.default_storage", "NORMALIZE_UTC");
        configuration.setProperty("hibernate.jdbc.time_zone", "UTC");
        return configuration.buildSessionFactory();
    }
}
