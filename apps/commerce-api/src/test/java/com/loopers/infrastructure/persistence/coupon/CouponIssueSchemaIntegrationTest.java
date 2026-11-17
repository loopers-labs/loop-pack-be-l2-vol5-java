package com.loopers.infrastructure.persistence.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.domain.coupon.model.Coupon;
import com.loopers.domain.coupon.model.CouponBenefit;
import com.loopers.domain.coupon.model.CouponCampaign;
import com.loopers.domain.coupon.model.CouponSlot;
import com.loopers.infrastructure.persistence.coupon.entity.CouponCampaignEntityMapper;
import com.loopers.infrastructure.persistence.coupon.entity.CouponCampaignJpaEntity;
import com.loopers.infrastructure.persistence.coupon.entity.CouponEntityMapper;
import com.loopers.infrastructure.persistence.coupon.entity.CouponJpaEntity;
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

class CouponIssueSchemaIntegrationTest {
    @Test
    @DisplayName("실제 012·013 DDL로 쿠폰의 복합 FK·유일 제약과 JPA validate·혜택/UTC 저장을 확인한다")
    void validatesActualCouponMigrationAndSnapshot() {
        // arrange
        try (MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("loopers").withUsername("test").withPassword("test")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci")
            .withCopyFileToContainer(MountableFile.forHostPath(migration("012-coupon-campaigns-slots.sql").toString()),
                "/docker-entrypoint-initdb.d/012-coupon-campaigns-slots.sql")
            .withCopyFileToContainer(MountableFile.forHostPath(migration("013-coupons.sql").toString()),
                "/docker-entrypoint-initdb.d/013-coupons.sql")
            .withStartupTimeout(Duration.ofSeconds(90))) {
            mysql.start();
            try (SessionFactory factory = new Configuration()
                .addAnnotatedClass(CouponCampaignJpaEntity.class).addAnnotatedClass(CouponSlotJpaEntity.class)
                .addAnnotatedClass(CouponJpaEntity.class)
                .setProperty("hibernate.connection.url", mysql.getJdbcUrl())
                .setProperty("hibernate.connection.username", mysql.getUsername())
                .setProperty("hibernate.connection.password", mysql.getPassword())
                .setProperty("hibernate.hbm2ddl.auto", "validate")
                .setProperty("hibernate.jdbc.time_zone", "UTC")
                .buildSessionFactory(); EntityManager entityManager = factory.createEntityManager()) {
                Instant now = Instant.parse("2026-11-13T01:02:03.123456Z");
                CouponBenefit benefit = new CouponBenefit("천 원 할인 🎟", 1_000);
                CouponCampaignJpaEntity campaign = new CouponCampaignEntityMapper().toNewEntity(
                    CouponCampaign.create("DDL 행사", 1, benefit, now, now.plusSeconds(60)));
                CouponEntityMapper mapper = new CouponEntityMapper();

                // act
                entityManager.getTransaction().begin();
                entityManager.persist(campaign);
                CouponSlotJpaEntity slot = new CouponSlotEntityMapper().toNewEntity(CouponSlot.create(campaign.getId()));
                entityManager.persist(slot);
                CouponJpaEntity coupon = mapper.toNewEntity(Coupon.create(campaign.getId(), slot.getId(), 1, benefit, now));
                entityManager.persist(coupon);
                entityManager.getTransaction().commit();
                entityManager.clear();

                // assert
                Coupon restored = mapper.toDomain(entityManager.find(CouponJpaEntity.class, coupon.getId()));
                assertThat(restored.getBenefit()).isEqualTo(benefit);
                assertThat(restored.getIssuedAt()).isEqualTo(now);
                assertThat(restored.getSlotId()).isEqualTo(slot.getId());
                Object[] definition = (Object[]) entityManager.createNativeQuery("SHOW CREATE TABLE coupons").getSingleResult();
                assertThat(definition[1].toString().replaceAll("\\s+", ""))
                    .contains("UNIQUEKEY`uk_coupons_campaign_user`(`campaign_id`,`user_id`)",
                        "UNIQUEKEY`uk_coupons_slot`(`slot_id`)",
                        "CONSTRAINT`fk_coupons_slot_campaign`FOREIGNKEY(`slot_id`,`campaign_id`)REFERENCES`coupon_slots`(`id`,`campaign_id`)",
                        "KEY`idx_coupons_user_issued_id`(`user_id`,`issued_at`DESC,`id`DESC)",
                        "ck_coupons_user", "ck_coupons_amount", "ENGINE=InnoDB")
                    .doesNotContain("REFERENCES`coupon_campaigns`", "NOTENFORCED");
            }
        }
    }

    private static Path migration(String fileName) {
        for (Path directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
            Path sql = directory.resolve("docker/payment-lab").resolve(fileName);
            if (Files.isRegularFile(sql)) {
                return sql;
            }
        }
        throw new IllegalStateException("쿠폰 명시 DDL을 찾을 수 없습니다: " + fileName);
    }
}
