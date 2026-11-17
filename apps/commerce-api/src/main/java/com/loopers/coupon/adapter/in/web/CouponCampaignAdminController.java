package com.loopers.coupon.adapter.in.web;

import com.loopers.coupon.adapter.in.web.dto.CouponCampaignAdminDto;
import com.loopers.coupon.adapter.in.web.spec.CouponCampaignAdminApiSpec;
import com.loopers.coupon.application.CouponCampaignAdminQueryService;
import com.loopers.coupon.application.CouponDeadLetterReplayService;
import com.loopers.coupon.application.port.in.CouponCampaignCommandUseCase;
import com.loopers.support.web.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api-admin/v1/coupon-campaigns")
public class CouponCampaignAdminController implements CouponCampaignAdminApiSpec {

    private final CouponCampaignCommandUseCase commandUseCase;
    private final CouponCampaignAdminQueryService queryService;
    private final CouponDeadLetterReplayService deadLetterReplayService;

    @PostMapping
    @Override
    public ApiResponse<CouponCampaignAdminDto.CampaignResponse> create(@RequestBody CouponCampaignAdminDto.CreateRequest request) {
        return ApiResponse.success(CouponCampaignAdminDto.CampaignResponse.from(commandUseCase.create(request.toCommand())));
    }

    @GetMapping("/{campaignId}")
    @Override
    public ApiResponse<CouponCampaignAdminDto.CampaignResponse> get(@PathVariable Long campaignId) {
        return ApiResponse.success(CouponCampaignAdminDto.CampaignResponse.from(queryService.get(campaignId)));
    }

    @PostMapping("/{campaignId}/dead-letters/replay")
    @Override
    public ApiResponse<CouponCampaignAdminDto.ReplayResponse> replayDeadLetters(@PathVariable Long campaignId) {
        queryService.get(campaignId); // 없는 캠페인이면 404
        return ApiResponse.success(new CouponCampaignAdminDto.ReplayResponse(deadLetterReplayService.replay(campaignId)));
    }
}
