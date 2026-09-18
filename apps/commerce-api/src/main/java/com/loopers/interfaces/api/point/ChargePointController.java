package com.loopers.interfaces.api.point;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.loopers.application.point.ChargePointFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.support.StrictLongDeserializer;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ChargePointController {
    private final ChargePointFacade facade;

    @PostMapping("/api/v1/points/charge")
    public ApiResponse<PointResponse> charge(@RequestHeader(value = "X-USER-ID", required = false) Long userId,
                                            @RequestBody ChargeRequest request) {
        return ApiResponse.success(new PointResponse(facade.charge(userId, request.amount())));
    }

    public record ChargeRequest(@JsonDeserialize(using = StrictLongDeserializer.class) Long amount) {}
}
