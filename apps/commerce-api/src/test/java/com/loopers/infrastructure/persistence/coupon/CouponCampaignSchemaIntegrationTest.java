package com.loopers.infrastructure.persistence.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.domain.coupon.model.CouponBenefit;
import com.loopers.domain.coupon.model.CouponCampaign;
import com.loopers.domain.coupon.model.CouponSlot;
import com.loopers.infrastructure.persistence.coupon.entity.CouponCampaignEntityMapper;
import com.loopers.infrastructure.persistence.coupon.entity.CouponCampaignJpaEntity;
import com.loopers.infrastructure.persistence.coupon.entity.CouponSlotEntityMapper;
import com.loopers.infrastructure.persistence.coupon.entity.CouponSlotJpaEntity;
import jakarta.persistence.EntityManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

class CouponCampaignSchemaIntegrationTest {
    @Test
    @DisplayName("실제 쿠폰 DDL의 제약과 인덱스를 확인하고 JPA validate 및 저장·재조회를 검증한다")
    void validatesExplicitSchemaAndPersistence() {
        // arrange: 자체 컨테이너에 실제 마이그레이션만 적용한다. 기존 DB/재사용 컨테이너는 건드리지 않는다.
        try (MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("loopers")
            .withUsername("test")
            .withPassword("test")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci")
            .withCopyFileToContainer(MountableFile.forHostPath(findMigration().toString()),
                "/docker-entrypoint-initdb.d/012-coupon-campaigns-slots.sql")
            .withStartupTimeout(Duration.ofSeconds(90))) {
            mysql.start();
            try (SessionFactory factory = new Configuration()
                .addAnnotatedClass(CouponCampaignJpaEntity.class)
                .addAnnotatedClass(CouponSlotJpaEntity.class)
                .setProperty("hibernate.connection.url", mysql.getJdbcUrl())
                .setProperty("hibernate.connection.username", mysql.getUsername())
                .setProperty("hibernate.connection.password", mysql.getPassword())
                .setProperty("hibernate.hbm2ddl.auto", "validate")
                .setProperty("hibernate.jdbc.time_zone", "UTC")
                .buildSessionFactory();
                 EntityManager entityManager = factory.createEntityManager()) {
                CouponCampaignEntityMapper campaignMapper = new CouponCampaignEntityMapper();
                CouponSlotEntityMapper slotMapper = new CouponSlotEntityMapper();
                Instant start = Instant.parse("2026-11-13T00:00:00.123456Z");
                CouponCampaignJpaEntity campaign = campaignMapper.toNewEntity(CouponCampaign.create(
                    "DDL 확인 🎉", 1, new CouponBenefit("할인 🎟", 1_000), start, start.plusSeconds(3600)));

                // act: validate는 스키마를 생성/수정하지 않는다. 실제 JPA 쓰기 뒤 컨텍스트를 비운다.
                entityManager.getTransaction().begin();
                entityManager.persist(campaign);
                CouponSlotJpaEntity slot = slotMapper.toNewEntity(CouponSlot.create(campaign.getId()));
                entityManager.persist(slot);
                entityManager.getTransaction().commit();
                entityManager.clear();

                // assert: 컬럼 매핑과 함께 validate가 검사하지 않는 실제 FK/인덱스/CHECK도 확인한다.
                CouponCampaign restored = campaignMapper.toDomain(entityManager.find(CouponCampaignJpaEntity.class, campaign.getId()));
                assertThat(restored.getBenefit()).isEqualTo(new CouponBenefit("할인 🎟", 1_000));
                assertThat(restored.getStartsAt()).isEqualTo(start);
                assertThat(slotMapper.toDomain(entityManager.find(CouponSlotJpaEntity.class, slot.getId())).getCampaignId())
                    .isEqualTo(campaign.getId());
                assertThat(tableDefinition(entityManager, "coupon_campaigns"))
                    .contains("ck_coupon_campaign_quota", "ck_coupon_campaign_amount", "ck_coupon_campaign_period", "ENGINE=InnoDB")
                    .doesNotContain("NOT ENFORCED");
                assertThat(tableDefinition(entityManager, "coupon_slots"))
                    .contains("CONSTRAINT `fk_coupon_slots_campaign` FOREIGN KEY (`campaign_id`) REFERENCES `coupon_campaigns` (`id`)",
                        "UNIQUE KEY `uk_coupon_slots_id_campaign` (`id`,`campaign_id`)",
                        "KEY `idx_coupon_slots_campaign_state_id` (`campaign_id`,`state`,`id`)",
                        "ck_coupon_slot_state", "ENGINE=InnoDB")
                    .doesNotContain("NOT ENFORCED");
            }
        }
    }

    private static Path findMigration() {
        for (Path directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
            Path migration = directory.resolve("docker/payment-lab/012-coupon-campaigns-slots.sql");
            if (Files.isRegularFile(migration)) {
                return migration;
            }
        }
        throw new IllegalStateException("쿠폰 캠페인/슬롯 명시 DDL을 찾을 수 없습니다.");
    }

    private static String tableDefinition(EntityManager entityManager, String tableName) {
        Object[] row = (Object[]) entityManager.createNativeQuery("SHOW CREATE TABLE " + tableName).getSingleResult();
        return row[1].toString();
    }
}
