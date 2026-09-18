package com.loopers.interfaces.api.point;

import com.loopers.application.point.GetPointFacade;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class GetPointController {
    private final GetPointFacade facade;

    @GetMapping("/api/v1/points")
    public ApiResponse<PointResponse> get(@RequestHeader(value = "X-USER-ID", required = false) Long userId) {
        return ApiResponse.success(new PointResponse(facade.get(userId)));
    }
}
