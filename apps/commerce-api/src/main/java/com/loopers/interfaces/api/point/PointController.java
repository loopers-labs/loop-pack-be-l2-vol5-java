package com.loopers.interfaces.api.point;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.application.user.BalanceInfo;
import com.loopers.application.user.PointService;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.commerce.InvalidRequestException;
import com.loopers.interfaces.api.commerce.StrictInput;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1/points", produces = MediaType.APPLICATION_JSON_VALUE)
public class PointController {

    private final PointService pointService;

    public PointController(PointService pointService) {
        this.pointService = pointService;
    }

    @PostMapping(value = "/charge", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<BalanceInfo> charge(
        @RequestHeader(value = "X-USER-ID", required = false) String requester,
        @RequestBody JsonNode body
    ) {
        StrictInput.object(body, "amount");
        long amount = StrictInput.number(body, "amount");
        if (amount <= 0L) {
            throw new InvalidRequestException();
        }
        return ApiResponse.success(pointService.charge(requester, amount));
    }

    @GetMapping
    public ApiResponse<BalanceInfo> balance(
        @RequestHeader(value = "X-USER-ID", required = false) String requester
    ) {
        return ApiResponse.success(pointService.balance(requester));
    }
}
