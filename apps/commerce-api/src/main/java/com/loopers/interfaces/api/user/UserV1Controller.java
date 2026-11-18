package com.loopers.interfaces.api.user;

import com.loopers.application.user.UserFacade;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
public class UserV1Controller implements UserV1ApiSpec {

    private final UserFacade userFacade;

    @PostMapping("/api/v1/users")
    @Override
    public ApiResponse<UserV1Dto.UserResponse> create(@RequestBody(required = false) UserV1Dto.CreateRequest request) {
        var info = userFacade.create(request == null ? null : request.admin());
        return ApiResponse.success(UserV1Dto.UserResponse.from(info));
    }
}
