package com.loopers.interfaces.api.coupon;

import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.SchemaProperty;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@Tag(name = "Coupon V1 API", description = "고객 쿠폰 대기 등록·조회·발급·보유 목록 API")
public interface CouponV1ApiSpec {

    @Operation(summary = "대기 등록", description = "신규 201 WAITING, 유효 항목 재등록·회수 전 만료 안내·기존 쿠폰 200. "
        + "등록 요청률 초과 429 COUPON_RATE_LIMITED(사용자 확인 전), 운영 캠페인 아님·닫힘 503, 캠페인 없음 404, 비활성·품절 409.")
    ResponseEntity<ApiResponse<CouponV1Dto.WaitingResponse>> registerWaiting(Long userId, Long campaignId);

    @Operation(summary = "대기 조회", description = "기존 쿠폰은 ISSUED·couponId. 등록 이력 없음 404 COUPON_WAITING_NOT_FOUND, "
        + "운영 캠페인 아님 503. 수용 닫힘만으로는 막지 않으며 상태를 바꾸지 않는다.")
    ApiResponse<CouponV1Dto.WaitingResponse> getWaiting(Long userId, Long campaignId);

    @Operation(summary = "발급", description = "X-WAITING-ID 헤더의 입장 권한으로 발급한다. 기존 쿠폰은 헤더 없이도 200. "
        + "헤더 누락 400 COUPON_WAITING_ID_REQUIRED, ID 없음·불일치 404 COUPON_WAITING_NOT_FOUND, "
        + "대기 중·만료·이미 사용 409(NOT_READY·EXPIRED·ALREADY_USED), 비활성·품절 409, 닫힘·운영 캠페인 아님 503.")
    // 실제 응답은 공통 ApiResponse(meta, data)다. 상태별로 data 유형이 달라 제네릭 대신 meta·data 속성을 직접 적는다.
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "신규 발급 commit 확인",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schemaProperties = {
                @SchemaProperty(name = "meta", schema = @Schema(implementation = ApiResponse.Metadata.class)),
                @SchemaProperty(name = "data", schema = @Schema(implementation = CouponV1Dto.CouponResponse.class))})),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "기존 쿠폰(같은 couponId)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schemaProperties = {
                @SchemaProperty(name = "meta", schema = @Schema(implementation = ApiResponse.Metadata.class)),
                @SchemaProperty(name = "data", schema = @Schema(implementation = CouponV1Dto.CouponResponse.class))})),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "같은 권한의 발급 진행 중(PROCESSING). 추가 실행·예약 없음",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schemaProperties = {
                @SchemaProperty(name = "meta", schema = @Schema(implementation = ApiResponse.Metadata.class)),
                @SchemaProperty(name = "data", schema = @Schema(implementation = CouponV1Dto.WaitingResponse.class))}))
    })
    ResponseEntity<ApiResponse<?>> issue(Long userId, Long campaignId, String waitingId);

    ApiResponse<PageResponse<CouponV1Dto.OwnedCouponResponse>> getMyCoupons(
        Long userId, Integer page, Integer size, String sort);
}
