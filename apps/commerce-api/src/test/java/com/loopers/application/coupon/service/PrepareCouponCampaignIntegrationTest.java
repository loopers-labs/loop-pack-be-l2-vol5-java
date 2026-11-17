package com.loopers.application.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loopers.application.coupon.command.CouponCampaignCommand;
import com.loopers.application.coupon.result.CouponCampaignResult;
import com.loopers.domain.coupon.model.CouponBenefit;
import com.loopers.domain.coupon.model.CouponCampaign;
import com.loopers.domain.coupon.model.CouponSlot;
import com.loopers.domain.coupon.model.CouponSlotState;
import com.loopers.domain.coupon.repository.CouponCampaignRepository;
import com.loopers.domain.coupon.repository.CouponSlotRepository;
import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;
import com.loopers.infrastructure.persistence.coupon.entity.CouponSlotJpaEntity;
import com.loopers.infrastructure.persistence.coupon.jpa.CouponSlotJpaRepository;
import com.loopers.infrastructure.persistence.coupon.repository.CouponSlotRepositoryImpl;
import com.loopers.support.test.IntegrationTest;
import com.loopers.utils.DatabaseCleanUp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
@Import(PrepareCouponCampaignIntegrationTest.FailureConfiguration.class)
class PrepareCouponCampaignIntegrationTest {
    private static final Instant START = Instant.parse("2026-11-13T01:02:03.123456789Z");
    private static final Instant END = Instant.parse("2026-11-14T01:02:03.987654321Z");

    @Autowired
    private PrepareCouponCampaignService service;
    @Autowired
    private CouponCampaignRepository campaigns;
    @Autowired
    private CouponSlotJpaRepository slots;
    @Autowired
    private FailingSlotRepository failingSlots;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        failingSlots.reset();
        databaseCleanUp.truncateAllTables();
    }

    @Test
    @DisplayName("총량만큼 슬롯을 준비한 뒤 명시적으로 활성화하며 혜택과 UTC 마이크로초 시각을 보존한다")
    void preparesCompleteInactiveCampaignAndExplicitlyActivates() {
        // arrange
        CouponCampaignCommand.Create command = command();

        // act
        CouponCampaignResult prepared = service.execute(command);
        CouponCampaign stored = campaigns.findById(prepared.campaignId()).orElseThrow();
        CouponCampaignResult activated = service.execute(new CouponCampaignCommand.Activation(prepared.campaignId(), true));

        // assert
        assertThat(prepared.active()).isFalse();
        assertThat(prepared.preparedSlotCount()).isEqualTo(3);
        assertThat(stored.getIssuedCount()).isZero();
        assertThat(stored.getName()).isEqualTo("쿠폰 행사 🎉");
        assertThat(stored.getBenefit()).isEqualTo(new CouponBenefit("천 원 할인 🎟", 1_000));
        assertThat(stored.getStartsAt()).isEqualTo(START.truncatedTo(ChronoUnit.MICROS));
        assertThat(stored.getEndsAt()).isEqualTo(END.truncatedTo(ChronoUnit.MICROS));
        assertThat(slots.findAll()).hasSize(3).allSatisfy(slot -> {
            assertThat(slot.getCampaignId()).isEqualTo(prepared.campaignId());
            assertThat(slot.getState()).isEqualTo(CouponSlotState.AVAILABLE);
        });
        assertThat(activated.active()).isTrue();
        assertThat(campaigns.findById(prepared.campaignId()).orElseThrow().isActive()).isTrue();
    }

    @Test
    @DisplayName("슬롯 두 장을 실제 저장한 뒤 실패하면 서비스 트랜잭션이 캠페인과 슬롯 전체를 롤백한다")
    void rollsBackCampaignAndAlreadyInsertedSlots() {
        // arrange
        failingSlots.failAfterSaving(2);

        // act
        assertThatThrownBy(() -> service.execute(command()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("QA: 슬롯 저장 후 실패");

        // assert
        assertThat(failingSlots.savedCount).isEqualTo(2);
        assertThat(campaigns.findById(failingSlots.lastCampaignId)).isEmpty();
        assertThat(slots.countByCampaignId(failingSlots.lastCampaignId)).isZero();
    }

    @Test
    @DisplayName("이미 발급한 캠페인을 재준비하고 다시 활성화해도 슬롯 ID·발급 상태·집계·혜택을 초기화하지 않는다")
    void preservesExistingInventoryAcrossPrepareAndReactivation() {
        // arrange
        long campaignId = service.execute(command()).campaignId();
        service.execute(new CouponCampaignCommand.Activation(campaignId, true));
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            CouponCampaign campaign = campaigns.findByIdForUpdate(campaignId).orElseThrow();
            CouponSlot slot = failingSlots.findAvailableForUpdateSkipLocked(campaignId).orElseThrow();
            slot.markIssued();
            failingSlots.save(slot);
            campaign.recordIssue(START.plusSeconds(1));
            campaigns.save(campaign);
        });
        List<Long> originalIds = slots.findAll().stream().map(CouponSlotJpaEntity::getId).sorted().toList();
        service.execute(new CouponCampaignCommand.Activation(campaignId, false));

        // act
        CouponCampaignResult prepared = service.execute(new CouponCampaignCommand.Prepare(campaignId));
        CouponCampaignResult activated = service.execute(new CouponCampaignCommand.Activation(campaignId, true));

        // assert
        assertThat(prepared.active()).isFalse();
        assertThat(prepared.preparedSlotCount()).isEqualTo(3);
        assertThat(activated.issuedCount()).isEqualTo(1);
        assertThat(activated.active()).isTrue();
        assertThat(slots.findAll().stream().map(CouponSlotJpaEntity::getId).sorted().toList()).isEqualTo(originalIds);
        assertThat(slots.findAll()).filteredOn(slot -> slot.getState() == CouponSlotState.ISSUED).hasSize(1);
        CouponCampaign stored = campaigns.findById(campaignId).orElseThrow();
        assertThat(stored.getIssuedCount()).isEqualTo(1);
        assertThat(stored.getBenefit()).isEqualTo(new CouponBenefit("천 원 할인 🎟", 1_000));
        assertThat(stored.getStartsAt()).isEqualTo(START.truncatedTo(ChronoUnit.MICROS));
        assertThat(stored.getEndsAt()).isEqualTo(END.truncatedTo(ChronoUnit.MICROS));
    }

    @Test
    @DisplayName("일부 슬롯만 존재하는 캠페인은 자동 보충하거나 활성화하지 않고 기존 재고를 보존한다")
    void rejectsPartiallyPreparedCampaignWithoutAddingSlots() {
        // arrange
        long campaignId = new TransactionTemplate(transactionManager).execute(status -> {
            CouponCampaign campaign = campaigns.save(CouponCampaign.create("부분 준비", 3,
                new CouponBenefit("혜택", 1_000), START, END));
            failingSlots.save(CouponSlot.create(campaign.getId()));
            return campaign.getId();
        });

        // act / assert
        assertThatThrownBy(() -> service.execute(new CouponCampaignCommand.Prepare(campaignId)))
            .isInstanceOfSatisfying(DomainException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(DomainErrorCode.COUPON_SLOTS_NOT_READY));
        assertThatThrownBy(() -> service.execute(new CouponCampaignCommand.Activation(campaignId, true)))
            .isInstanceOfSatisfying(DomainException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(DomainErrorCode.COUPON_SLOTS_NOT_READY));
        assertThat(slots.countByCampaignId(campaignId)).isEqualTo(1);
        CouponCampaign stored = campaigns.findById(campaignId).orElseThrow();
        assertThat(stored.isActive()).isFalse();
        assertThat(stored.getIssuedCount()).isZero();
    }

    @Test
    @DisplayName("외부 REPEATABLE READ의 빈 슬롯 스냅샷과 격리하여 재준비 때 총량을 초과해 생성하지 않는다")
    void isolatesPreparationFromOuterRepeatableReadSnapshot() {
        // arrange
        long campaignId = campaigns.save(CouponCampaign.create("스냅샷 격리", 3,
            new CouponBenefit("혜택", 1_000), START, END)).getId();
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

        // act
        outer.executeWithoutResult(status -> {
            assertThat(slots.countByCampaignId(campaignId)).isZero();
            assertThat(service.execute(new CouponCampaignCommand.Prepare(campaignId)).preparedSlotCount()).isEqualTo(3);
            assertThat(slots.countByCampaignId(campaignId)).isZero();
            assertThat(service.execute(new CouponCampaignCommand.Prepare(campaignId)).preparedSlotCount()).isEqualTo(3);
        });

        // assert
        assertThat(slots.countByCampaignId(campaignId)).isEqualTo(3);
        assertThat(campaigns.findById(campaignId).orElseThrow().isActive()).isFalse();
    }

    private CouponCampaignCommand.Create command() {
        return new CouponCampaignCommand.Create(" 쿠폰 행사 🎉 ", 3, " 천 원 할인 🎟 ", 1_000, START, END);
    }

    @TestConfiguration
    static class FailureConfiguration {
        @Bean
        @Primary
        FailingSlotRepository failingSlotRepository(CouponSlotRepositoryImpl delegate) {
            return new FailingSlotRepository(delegate);
        }
    }

    /** 실제 JPA 저장 이후 한 번 실패시켜 서비스 프록시의 트랜잭션 롤백을 검증한다. */
    static class FailingSlotRepository implements CouponSlotRepository {
        private final CouponSlotRepository delegate;
        private int failureAt;
        private int savedCount;
        private long lastCampaignId;

        FailingSlotRepository(CouponSlotRepository delegate) {
            this.delegate = delegate;
        }

        void failAfterSaving(int count) {
            reset();
            failureAt = count;
        }

        void reset() {
            failureAt = 0;
            savedCount = 0;
            lastCampaignId = 0;
        }

        @Override
        public CouponSlot save(CouponSlot slot) {
            CouponSlot saved = delegate.save(slot);
            savedCount++;
            lastCampaignId = saved.getCampaignId();
            if (savedCount == failureAt) {
                throw new IllegalStateException("QA: 슬롯 저장 후 실패");
            }
            return saved;
        }

        @Override
        public Optional<CouponSlot> findAvailableForUpdateSkipLocked(long campaignId) {
            return delegate.findAvailableForUpdateSkipLocked(campaignId);
        }

        @Override
        public long countByCampaignId(long campaignId) {
            return delegate.countByCampaignId(campaignId);
        }
    }
}
