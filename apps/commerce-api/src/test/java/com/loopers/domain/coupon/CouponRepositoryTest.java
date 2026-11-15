package com.loopers.domain.coupon;

import com.loopers.infrastructure.coupon.CouponCampaignJpaRepository;
import com.loopers.infrastructure.coupon.CouponJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class CouponRepositoryTest {

    @Autowired
    private CouponCampaignRepository campaignRepository;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private CouponCampaignJpaRepository campaignJpaRepository;

    @Autowired
    private CouponJpaRepository couponJpaRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    /** 조건부 UPDATE는 운영에서도 발급 트랜잭션 안에서 호출되므로, 테스트도 트랜잭션 안에서 부른다. */
    private boolean increase(Long campaignId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> campaignRepository.increaseIssuedCount(campaignId)));
    }

    @DisplayName("같은 캠페인에서 같은 사용자의 쿠폰을 두 번 저장하면, UNIQUE 제약이 막는다.")
    @Test
    void rejectsSecondCouponOfSameUserInCampaign() {
        // arrange
        Long campaignId = campaignRepository.save(new CouponCampaignModel("신규 회원", 10, "5,000원 할인권")).getId();
        couponRepository.saveAndFlush(new CouponModel(campaignId, 1L, "5,000원 할인권", ZonedDateTime.now()));

        // act & assert
        assertThatThrownBy(() -> couponRepository.saveAndFlush(new CouponModel(campaignId, 1L, "5,000원 할인권", ZonedDateTime.now())))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(couponJpaRepository.count()).isEqualTo(1);
    }

    @DisplayName("다른 사용자이거나 다른 캠페인이면, 같은 사용자 ID라도 저장된다.")
    @Test
    void allowsSameUserInOtherCampaignAndOtherUserInSameCampaign() {
        // arrange
        Long first = campaignRepository.save(new CouponCampaignModel("A", 10, "혜택")).getId();
        Long second = campaignRepository.save(new CouponCampaignModel("B", 10, "혜택")).getId();

        // act
        couponRepository.saveAndFlush(new CouponModel(first, 1L, "혜택", ZonedDateTime.now()));
        couponRepository.saveAndFlush(new CouponModel(first, 2L, "혜택", ZonedDateTime.now()));
        couponRepository.saveAndFlush(new CouponModel(second, 1L, "혜택", ZonedDateTime.now()));

        // assert
        assertThat(couponJpaRepository.count()).isEqualTo(3);
    }

    @DisplayName("내 목록 인덱스는 (user_id 오름차순, issued_at·id 내림차순)으로 실제 DDL에 만들어지고, 목록 조회가 이 인덱스를 쓴다.")
    @Test
    void listIndexIsCreatedWithDescendingColumns_andUsedByTheListQuery() {
        // act
        @SuppressWarnings("unchecked")
        List<Object[]> indexRows = entityManager
            .createNativeQuery("show index from coupon where Key_name = 'idx_coupon_user_issued'").getResultList();
        @SuppressWarnings("unchecked")
        List<Object[]> plan = entityManager.createNativeQuery(
            "explain select * from coupon where user_id = 1 order by issued_at desc, id desc").getResultList();

        // assert — SHOW INDEX 열: [3]=Seq_in_index, [4]=Column_name, [5]=Collation(A 오름차순, D 내림차순)
        assertThat(indexRows).extracting(row -> row[4] + ":" + row[5]).containsExactly("user_id:A", "issued_at:D", "id:D");
        // EXPLAIN 열: [5]=key — 정렬을 위한 별도 filesort 없이 이 인덱스를 쓴다
        assertThat(plan).extracting(row -> String.valueOf(row[5])).contains("idx_coupon_user_issued");
    }

    @DisplayName("발급 수 증가는 한도에 닿으면 영향 행 0으로 실패하고, 한도를 넘기지 않는다.")
    @Test
    void increaseIssuedCountStopsAtQuota() {
        // arrange
        Long campaignId = campaignRepository.save(new CouponCampaignModel("한정", 2, "혜택")).getId();

        // act & assert
        assertThat(increase(campaignId)).isTrue();
        assertThat(increase(campaignId)).isTrue();
        assertThat(increase(campaignId)).isFalse();
        assertThat(campaignJpaRepository.findById(campaignId).orElseThrow().getIssuedCount()).isEqualTo(2);
    }

    @DisplayName("비활성 캠페인은, 발급 수가 한도보다 작아도 증가하지 않는다.")
    @Test
    void increaseIssuedCountRejectsInactiveCampaign() {
        // arrange
        CouponCampaignModel campaign = new CouponCampaignModel("비활성", 5, "혜택");
        campaign.deactivate();
        Long campaignId = campaignRepository.save(campaign).getId();

        // act & assert
        assertThat(increase(campaignId)).isFalse();
        assertThat(campaignJpaRepository.findById(campaignId).orElseThrow().getIssuedCount()).isZero();
    }
}
