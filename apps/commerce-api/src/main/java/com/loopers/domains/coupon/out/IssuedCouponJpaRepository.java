package com.loopers.domains.coupon.out;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IssuedCouponJpaRepository extends JpaRepository<IssuedCouponJpaEntity, Long> {
    @Query("select i from IssuedCouponJpaEntity i where i.userId = :userId order by i.issuedAt desc, i.id desc")
    List<IssuedCouponJpaEntity> findByUserNewestFirst(@Param("userId") long userId);
}
