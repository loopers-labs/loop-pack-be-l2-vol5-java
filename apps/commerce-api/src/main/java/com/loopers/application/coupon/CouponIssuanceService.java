package com.loopers.application.coupon;

import com.loopers.application.user.UserResolutionException;
import com.loopers.application.user.UserResolver;
import com.loopers.domain.common.PageResult;
import com.loopers.domain.coupon.CouponCampaign;
import com.loopers.domain.coupon.CouponException;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.domain.coupon.CouponReservation;
import com.loopers.domain.coupon.CouponStatus;
import com.loopers.domain.coupon.OwnedCoupon;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class CouponIssuanceService {
    private final CouponRepository coupons;
    private final UserRepository users;
    private final UserResolver userResolver;

    public CouponIssuanceService(CouponRepository coupons, UserRepository users, UserResolver userResolver) {
        this.coupons = coupons;
        this.users = users;
        this.userResolver = userResolver;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CouponReservationInfo reserve(long campaignId, long userId) {
        CouponCampaign campaign = lockCampaign(campaignId);
        var existing = coupons.findReservation(campaignId, userId);
        if (existing.isPresent()) {
            return reservationInfo(existing.get());
        }
        User user = users.findById(userId).orElseThrow(UserResolutionException::userNotFound);
        campaign.reserveOne();
        CouponReservation reservation = coupons.saveReservation(new CouponReservation(campaign, user));
        return CouponReservationInfo.from(reservation, null);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CouponReservationInfo issue(long campaignId, long userId) {
        CouponCampaign campaign = lockCampaign(campaignId);
        CouponReservation reservation = coupons.findReservation(campaignId, userId)
            .orElseThrow(() -> new CouponException(CouponException.Reason.RESERVATION_NOT_FOUND));
        if (reservation.getStatus() == CouponStatus.ISSUED) {
            return reservationInfo(reservation);
        }
        campaign.issueOne();
        reservation.markIssued();
        OwnedCoupon coupon = coupons.saveCoupon(new OwnedCoupon(reservation, campaign.getBenefitDescription()));
        return CouponReservationInfo.from(reservation, coupon.getId());
    }

    @Transactional(readOnly = true)
    public List<CouponReservationInfo> findWaiting(List<Long> campaignIds, int limit) {
        return coupons.findWaiting(campaignIds, limit).stream()
            .map(reservation -> CouponReservationInfo.from(reservation, null))
            .toList();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CouponReservationInfo verifyIssuance(long campaignId, long userId) {
        // A read taking the same lock waits for the previous issuing transaction to finish.
        lockCampaign(campaignId);
        return coupons.findReservation(campaignId, userId).map(this::reservationInfo)
            .orElseThrow(() -> new CouponException(CouponException.Reason.RESERVATION_NOT_FOUND));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<CouponReservationInfo> findReservation(long campaignId, String requester) {
        long userId = userResolver.resolve(requester).userId();
        var reservation = findCommitted(campaignId, userId);
        if (reservation.isEmpty() && !coupons.campaignExists(campaignId)) {
            throw new CouponException(CouponException.Reason.CAMPAIGN_NOT_FOUND);
        }
        return reservation;
    }

    @Transactional(readOnly = true)
    public Optional<CouponReservationInfo> findCommitted(long campaignId, long userId) {
        return coupons.findReservation(campaignId, userId).map(this::reservationInfo);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CampaignSelectionSnapshot selectionSnapshot(long campaignId) {
        CouponCampaign campaign = lockCampaign(campaignId);
        List<Long> members = coupons.reservedUserIds(campaignId);
        if (members.size() != campaign.allocatedCount()) {
            throw new IllegalStateException("Campaign counters disagree with durable reservations: " + campaignId);
        }
        return new CampaignSelectionSnapshot(campaign.getTotalQuota(), campaign.isActive(), members);
    }

    public record CampaignSelectionSnapshot(int totalQuota, boolean active, List<Long> reservedUsers) {
        public CampaignSelectionSnapshot {
            reservedUsers = List.copyOf(reservedUsers);
        }
    }

    @Transactional(readOnly = true)
    public List<OwnedCouponInfo> listIssued(String requester, int page, int size) {
        long userId = userResolver.resolve(requester).userId();
        return issuedItems(userId, page, size);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<OwnedCouponInfo> pageIssued(String requester, int page, int size) {
        long userId = userResolver.resolve(requester).userId();
        long total = coupons.countOwned(userId);
        if ((long) page * size >= total) {
            return PageResult.of(List.of(), page, size, total);
        }
        return PageResult.of(issuedItems(userId, page, size), page, size, total);
    }

    private List<OwnedCouponInfo> issuedItems(long userId, int page, int size) {
        return coupons.findOwned(userId, page, size).stream().map(OwnedCouponInfo::from).toList();
    }

    private CouponCampaign lockCampaign(long campaignId) {
        return coupons.lockCampaign(campaignId)
            .orElseThrow(() -> new CouponException(CouponException.Reason.CAMPAIGN_NOT_FOUND));
    }

    private CouponReservationInfo reservationInfo(CouponReservation reservation) {
        if (reservation.getStatus() == CouponStatus.WAITING) {
            return CouponReservationInfo.from(reservation, null);
        }
        OwnedCoupon coupon = coupons.findCoupon(reservation.getId())
            .orElseThrow(() -> new IllegalStateException("Issued reservation has no owned coupon"));
        return CouponReservationInfo.from(reservation, coupon.getId());
    }
}
