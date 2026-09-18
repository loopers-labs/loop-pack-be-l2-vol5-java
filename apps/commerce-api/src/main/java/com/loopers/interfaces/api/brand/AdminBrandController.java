package com.loopers.interfaces.api.brand;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.application.brand.AdminBrandInfo;
import com.loopers.application.brand.AdminBrandQueryService;
import com.loopers.application.brand.AdminBrandService;
import com.loopers.application.brand.BrandRegistrationService;
import com.loopers.domain.common.PageResult;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.commerce.AdminRequester;
import com.loopers.interfaces.api.commerce.StrictInput;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api-admin/v1/brands", produces = "application/json")
public class AdminBrandController {
    private final BrandRegistrationService registration;
    private final AdminBrandQueryService queries;
    private final AdminBrandService changes;

    public AdminBrandController(BrandRegistrationService registration, AdminBrandQueryService queries, AdminBrandService changes) {
        this.registration = registration;
        this.queries = queries;
        this.changes = changes;
    }

    @PostMapping(consumes = "application/json")
    public ResponseEntity<ApiResponse<AdminBrandInfo>> create(
        Authentication requester, @RequestBody JsonNode body) {
        StrictInput.object(body, "name");
        return ResponseEntity.status(201).body(ApiResponse.success(registration.register(AdminRequester.role(requester), StrictInput.text(body, "name"))));
    }

    @GetMapping("/{brandId}")
    public ApiResponse<AdminBrandInfo> detail(
        Authentication requester, @PathVariable String brandId) {
        return ApiResponse.success(queries.getDetail(AdminRequester.role(requester), StrictInput.id(brandId)));
    }

    @GetMapping
    public ApiResponse<PageResult<AdminBrandInfo>> list(
        Authentication requester,
        @RequestParam(required = false) String page, @RequestParam(required = false) String size) {
        return ApiResponse.success(changes.list(AdminRequester.role(requester), StrictInput.page(page), StrictInput.size(size)));
    }

    @PutMapping(value = "/{brandId}", consumes = "application/json")
    public ApiResponse<AdminBrandInfo> rename(
        Authentication requester,
        @PathVariable String brandId, @RequestBody JsonNode body) {
        long id = StrictInput.id(brandId);
        StrictInput.object(body, "name");
        return ApiResponse.success(changes.rename(AdminRequester.role(requester), id, StrictInput.text(body, "name")));
    }

    @DeleteMapping("/{brandId}")
    public ApiResponse<DeletedBrand> delete(
        Authentication requester, @PathVariable String brandId) {
        long id = StrictInput.id(brandId);
        changes.delete(AdminRequester.role(requester), id);
        return ApiResponse.success(new DeletedBrand(id, true));
    }

    public record DeletedBrand(long brandId, boolean deleted) {
    }
}
