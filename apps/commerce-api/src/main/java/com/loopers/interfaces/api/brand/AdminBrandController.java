package com.loopers.interfaces.api.brand;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.application.brand.BrandApplicationService;
import com.loopers.application.brand.BrandResult;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api-admin/v1/brands")
public class AdminBrandController {
    private final BrandApplicationService service;

    public AdminBrandController(BrandApplicationService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CreatedResponse> create(@RequestBody CreateRequest request) {
        BrandResult result = service.create(request.validatedName());
        return ApiResponse.success(new CreatedResponse(result.id().value()));
    }

    @org.springframework.web.bind.annotation.GetMapping
    public ApiResponse<java.util.List<com.loopers.application.brand.AdminBrandResult>> list(
        @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") int page,
        @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int size) {
        com.loopers.interfaces.api.RequestValues.page(page, size);
        return ApiResponse.success(service.list(page, size));
    }

    @org.springframework.web.bind.annotation.GetMapping("/{id}")
    public ApiResponse<com.loopers.application.brand.AdminBrandResult> get(
        @org.springframework.web.bind.annotation.PathVariable long id) {
        return ApiResponse.success(service.getAdminBrand(id));
    }

    @org.springframework.web.bind.annotation.PutMapping("/{id}")
    public ApiResponse<BrandResult> change(@org.springframework.web.bind.annotation.PathVariable long id,
        @RequestBody CreateRequest request) {
        return ApiResponse.success(service.change(id, request.validatedName()));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/{id}")
    public ApiResponse<Object> delete(@org.springframework.web.bind.annotation.PathVariable long id) {
        service.delete(id);
        return ApiResponse.success();
    }

    public record CreateRequest(JsonNode name) {
        String validatedName() {
            if (name == null || !name.isTextual() || name.textValue().isBlank()
                || name.textValue().length() > 100) {
                throw new CoreException(ErrorType.BAD_REQUEST, "브랜드 이름은 공백이 아닌 1~100자 문자열이어야 합니다.");
            }
            return name.textValue();
        }
    }

    public record CreatedResponse(long id) {
    }
}
