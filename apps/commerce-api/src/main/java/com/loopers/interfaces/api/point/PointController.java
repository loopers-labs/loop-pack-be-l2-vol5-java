package com.loopers.interfaces.api.point;

import com.loopers.application.point.PointFacade;
import com.loopers.application.point.dto.PointInfo;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.Requester;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/points")
public class PointController implements PointApiSpec {

    private final PointFacade pointFacade;

    @PostMapping("/charge")
    @Override
    public ApiResponse<PointDto.PointResponse> charge(
        @Requester Long userId,
        @Valid @RequestBody PointDto.ChargeRequest request
    ) {
        PointInfo info = pointFacade.charge(userId, request.amount());
        return ApiResponse.success(PointDto.PointResponse.from(info));
    }

    @GetMapping
    @Override
    public ApiResponse<PointDto.PointResponse> getPoint(
        @Requester Long userId
    ) {
        PointInfo info = pointFacade.getBalance(userId);
        return ApiResponse.success(PointDto.PointResponse.from(info));
    }
}
