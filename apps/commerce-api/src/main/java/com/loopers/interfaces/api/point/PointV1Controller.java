package com.loopers.interfaces.api.point;

import com.loopers.application.point.PointFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.auth.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/points")
public class PointV1Controller implements PointV1ApiSpec {

    private final PointFacade pointFacade;

    @PostMapping("/charge")
    @Override
    public ApiResponse<PointV1Dto.PointResponse> charge(LoginUser loginUser, @RequestBody PointV1Dto.ChargeRequest request) {
        return ApiResponse.success(PointV1Dto.PointResponse.from(pointFacade.charge(loginUser.id(), request.amount())));
    }

    @GetMapping
    @Override
    public ApiResponse<PointV1Dto.PointResponse> getPoint(LoginUser loginUser) {
        return ApiResponse.success(PointV1Dto.PointResponse.from(pointFacade.getPoint(loginUser.id())));
    }
}
