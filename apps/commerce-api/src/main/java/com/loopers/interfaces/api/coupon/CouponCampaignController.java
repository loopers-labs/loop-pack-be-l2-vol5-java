package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.CouponAdmissionService;
import com.loopers.application.coupon.CouponIssuanceService;
import com.loopers.application.coupon.CouponReservationInfo;
import com.loopers.domain.coupon.CouponStatus;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.commerce.InvalidRequestException;
import com.loopers.interfaces.api.commerce.StrictInput;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;

@RestController
@RequestMapping(value = "/api/v1/coupon-campaigns", produces = MediaType.APPLICATION_JSON_VALUE)
public class CouponCampaignController {
    private final CouponAdmissionService admission;
    private final CouponIssuanceService issuance;

    public CouponCampaignController(CouponAdmissionService admission, CouponIssuanceService issuance) {
        this.admission = admission;
        this.issuance = issuance;
    }

    @PostMapping("/{campaignId}/issues")
    public ResponseEntity<ApiResponse<ReservationResponse>> apply(
        @RequestHeader(value = "X-USER-ID", required = false) String requester,
        @PathVariable String campaignId,
        InputStream body
    ) throws IOException {
        if (body.read() != -1) {
            throw new InvalidRequestException();
        }
        CouponReservationInfo saved = admission.apply(requester, StrictInput.id(campaignId));
        return ResponseEntity.status(saved.status() == CouponStatus.WAITING ? HttpStatus.ACCEPTED : HttpStatus.OK)
            .body(ApiResponse.success(ReservationResponse.from(saved)));
    }

    @GetMapping("/{campaignId}/issues/me")
    public ApiResponse<ReservationResponse> myReservation(
        @RequestHeader(value = "X-USER-ID", required = false) String requester,
        @PathVariable String campaignId
    ) {
        long id = StrictInput.id(campaignId);
        var status = issuance.findReservation(id, requester)
            .map(ReservationResponse::from)
            .orElseGet(() -> new ReservationResponse(id, null, "NOT_APPLIED", null));
        return ApiResponse.success(status);
    }

    public record ReservationResponse(long campaignId, Long reservationId, String status, Long couponId) {
        static ReservationResponse from(CouponReservationInfo saved) {
            return new ReservationResponse(saved.campaignId(), saved.reservationId(), saved.status().name(), saved.couponId());
        }
    }
}
