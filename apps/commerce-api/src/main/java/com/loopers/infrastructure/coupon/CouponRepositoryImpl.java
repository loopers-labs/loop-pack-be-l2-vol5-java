package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.CouponCampaign;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.domain.coupon.CouponReservation;
import com.loopers.domain.coupon.CouponStatus;
import com.loopers.domain.coupon.OwnedCoupon;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class CouponRepositoryImpl implements CouponRepository {
    private static final String SELECT_OWNED =
        "select c from OwnedCoupon c join fetch c.reservation r ";
    private static final String NEWEST_FIRST = " order by c.issuedAt desc, c.id desc";
    private final EntityManager entityManager;

    public CouponRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Optional<CouponCampaign> lockCampaign(long campaignId) {
        return Optional.ofNullable(entityManager.find(CouponCampaign.class, campaignId, LockModeType.PESSIMISTIC_WRITE));
    }

    @Override
    public boolean campaignExists(long campaignId) {
        return entityManager.createQuery("select count(c) from CouponCampaign c where c.id = :id", Long.class)
            .setParameter("id", campaignId)
            .getSingleResult() > 0;
    }

    @Override
    public Optional<CouponReservation> findReservation(long campaignId, long userId) {
        return entityManager.createQuery("""
                select r from CouponReservation r where r.campaign.id = :campaignId and r.user.id = :userId
                """, CouponReservation.class)
            .setParameter("campaignId", campaignId)
            .setParameter("userId", userId)
            .getResultList().stream().findFirst();
    }

    @Override
    public List<Long> reservedUserIds(long campaignId) {
        return entityManager.createQuery("select r.user.id from CouponReservation r where r.campaign.id = :campaignId", Long.class)
            .setParameter("campaignId", campaignId)
            .getResultList();
    }

    @Override
    public List<CouponReservation> findWaiting(List<Long> campaignIds, int limit) {
        return entityManager.createQuery("""
                select r from CouponReservation r
                where r.campaign.id in :campaignIds and r.status = :waiting order by r.id
                """, CouponReservation.class)
            .setParameter("campaignIds", campaignIds)
            .setParameter("waiting", CouponStatus.WAITING)
            .setMaxResults(limit)
            .getResultList();
    }

    @Override
    public CouponReservation saveReservation(CouponReservation reservation) {
        entityManager.persist(reservation);
        return reservation;
    }

    @Override
    public OwnedCoupon saveCoupon(OwnedCoupon coupon) {
        entityManager.persist(coupon);
        return coupon;
    }

    @Override
    public Optional<OwnedCoupon> findCoupon(long reservationId) {
        return entityManager.createQuery("select c from OwnedCoupon c where c.reservation.id = :id", OwnedCoupon.class)
            .setParameter("id", reservationId)
            .getResultList().stream().findFirst();
    }

    @Override
    public long countOwned(long userId) {
        return entityManager.createQuery("select count(c) from OwnedCoupon c where c.reservation.user.id = :userId", Long.class)
            .setParameter("userId", userId)
            .getSingleResult();
    }

    @Override
    public List<OwnedCoupon> findOwned(long userId, int page, int size) {
        long offset = (long) page * size;
        if (offset <= Integer.MAX_VALUE) {
            return entityManager.createQuery(SELECT_OWNED + "where r.user.id = :userId" + NEWEST_FIRST, OwnedCoupon.class)
                .setParameter("userId", userId)
                .setFirstResult((int) offset)
                .setMaxResults(size)
                .getResultList();
        }

        // JPA's firstResult accepts only int; MySQL can apply the full offset.
        List<?> ids = entityManager.createNativeQuery("""
                select c.id from owned_coupon c join coupon_reservation r on r.id = c.reservation_id
                where r.user_id = :userId order by c.issued_at desc, c.id desc limit :size offset :offset
                """, Long.class)
            .setParameter("userId", userId)
            .setParameter("size", size)
            .setParameter("offset", offset)
            .getResultList();
        if (ids.isEmpty()) {
            return List.of();
        }
        return entityManager.createQuery(SELECT_OWNED + "where c.id in :ids" + NEWEST_FIRST, OwnedCoupon.class)
            .setParameter("ids", ids)
            .getResultList();
    }
}
