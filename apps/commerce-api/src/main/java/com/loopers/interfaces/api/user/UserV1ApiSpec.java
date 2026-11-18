package com.loopers.interfaces.api.user;

import com.loopers.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "User V1 API", description = "사용자 API (테스트용)")
public interface UserV1ApiSpec {

    /** POST /api/v1/users */
    @Operation(summary = "사용자 생성 (테스트용)",
        description = "헤더 없이 누구나 부른다. admin 이 true 면 관리자, 없거나 false 면 일반 사용자. 잔액 0 인 포인트 계정을 함께 만든다.")
    ApiResponse<UserV1Dto.UserResponse> create(UserV1Dto.CreateRequest request);
}
