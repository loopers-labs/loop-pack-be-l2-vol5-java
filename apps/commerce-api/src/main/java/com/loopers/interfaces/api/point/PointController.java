package com.loopers.interfaces.api.point;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.application.point.PointApplicationService;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.CustomerIdentity;
import com.loopers.interfaces.api.RequestValues;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PointController {
    private final PointApplicationService service;
    private final CustomerIdentity identity;
    public PointController(PointApplicationService service, CustomerIdentity identity) {
        this.service = service;
        this.identity = identity;
    }
    @GetMapping("/api/v1/points")
    public ApiResponse<BalanceResponse> balance(@RequestHeader(value = "X-USER-ID", required = false) String user) {
        return ApiResponse.success(new BalanceResponse(service.balance(identity.require(user))));
    }
    @PostMapping("/api/v1/points/charge")
    public ApiResponse<BalanceResponse> charge(@RequestHeader(value = "X-USER-ID", required = false) String user,
        @RequestBody ChargeRequest request) {
        return ApiResponse.success(new BalanceResponse(service.charge(identity.require(user), RequestValues.integer(request.amount()))));
    }
    public record ChargeRequest(JsonNode amount) { }
    public record BalanceResponse(long balance) { }
}
