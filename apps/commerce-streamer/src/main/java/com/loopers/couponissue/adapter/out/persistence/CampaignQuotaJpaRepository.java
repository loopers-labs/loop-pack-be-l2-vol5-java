package com.loopers.couponissue.adapter.out.persistence;

import com.loopers.couponissue.domain.CampaignQuota;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CampaignQuotaJpaRepository extends JpaRepository<CampaignQuota, Long> {

    /**
     * 판단과 변경을 한 문장으로 한다 (D-5). 바꾸는 경로가 consumer 하나뿐이라 비관적 잠금(W3 재고) 대신 이것으로 충분하다.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE CampaignQuota c SET c.issuedCount = c.issuedCount + 1 WHERE c.id = :id AND c.issuedCount < c.totalQuantity")
    int increaseIssuedCountIfAvailable(@Param("id") Long id);
}
