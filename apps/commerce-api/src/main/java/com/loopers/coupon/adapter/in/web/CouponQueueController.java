package com.loopers.coupon.adapter.in.web;

import com.loopers.coupon.adapter.in.web.dto.CouponQueueDto;
import com.loopers.coupon.application.port.in.CouponIssueUseCase;
import com.loopers.coupon.application.port.in.CouponIssueUseCase.IssueOutcome;
import com.loopers.coupon.application.port.in.CouponQueueUseCase;
import com.loopers.support.web.ApiResponse;
import com.loopers.user.adapter.in.web.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Coupon Queue V1 API", description = "선착순 쿠폰 대기열·발급 (X-USER-ID 필요)")
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/coupon-campaigns/{campaignId}")
public class CouponQueueController {

    private final CouponQueueUseCase queueUseCase;
    private final CouponIssueUseCase issueUseCase;

    @Operation(summary = "대기열 등록", description = "도착 순서대로 줄을 세웁니다. 다시 불러도 원래 자리입니다. 줄이 비어 있으면 바로 입장합니다.")
    @PostMapping("/queue")
    public ApiResponse<CouponQueueDto.QueueResponse> register(LoginUser loginUser, @PathVariable Long campaignId) {
        return ApiResponse.success(CouponQueueDto.QueueResponse.from(queueUseCase.register(campaignId, loginUser.id())));
    }

    @Operation(summary = "내 대기 상태", description = "WAITING이면 앞 인원과 다음 조회 시점(pollAfterMs)을 줍니다.")
    @GetMapping("/queue")
    public ApiResponse<CouponQueueDto.QueueResponse> status(LoginUser loginUser, @PathVariable Long campaignId) {
        return ApiResponse.success(CouponQueueDto.QueueResponse.from(queueUseCase.status(campaignId, loginUser.id())));
    }

    @Operation(summary = "쿠폰 발급", description = "입장한 사람만. 당첨이면 202(처리 중), 이미 발급됐으면 200 + 쿠폰 id들.")
    @PostMapping("/issues")
    public ResponseEntity<ApiResponse<CouponQueueDto.IssueResponse>> issue(LoginUser loginUser, @PathVariable Long campaignId) {
        IssueOutcome outcome = issueUseCase.issue(campaignId, loginUser.id());
        HttpStatus status = outcome.status() == IssueOutcome.Status.ISSUED ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(ApiResponse.success(CouponQueueDto.IssueResponse.from(outcome)));
    }
}
