package com.loopers.interfaces.api.brand;

import com.loopers.application.brand.DeleteBrandFacade;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;

@RestController
@RequiredArgsConstructor
public class DeleteBrandController {
    private final DeleteBrandFacade facade;
    @DeleteMapping("/api-admin/v1/brands/{brandId}")
    public ApiResponse<Object> delete(@PathVariable long brandId) {
        facade.delete(brandId);
        return ApiResponse.success();
    }
}
