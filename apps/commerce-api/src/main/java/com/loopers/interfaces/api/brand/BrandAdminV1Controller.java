package com.loopers.interfaces.api.brand;

import com.loopers.application.brand.BrandFacade;
import com.loopers.domain.common.PageWindow;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.common.PageNumber;
import com.loopers.domain.common.PageSize;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api-admin/v1/brands")
@RequiredArgsConstructor
public class BrandAdminV1Controller {

    private final BrandFacade brandFacade;
    private final BrandService brandService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<BrandV1Dto.BrandResponse> register(@RequestBody BrandAdminV1Dto.RegisterRequest request) {
        return ApiResponse.success(
            BrandV1Dto.BrandResponse.from(brandFacade.register(request.name(), request.description()))
        );
    }

    @GetMapping
    public ApiResponse<BrandAdminV1Dto.AdminBrandPageResponse> findPage(
        @RequestParam(defaultValue = "0") PageNumber page,
        @RequestParam(defaultValue = "20") PageSize size
    ) {
        return ApiResponse.success(BrandAdminV1Dto.AdminBrandPageResponse.of(
            brandService.findPage(page.offsetWith(size), PageWindow.limitOf(size)), page, size));
    }

    @GetMapping("/{brandId}")
    public ApiResponse<BrandV1Dto.BrandResponse> get(@PathVariable Long brandId) {
        return ApiResponse.success(BrandV1Dto.BrandResponse.from(brandService.get(brandId)));
    }

    @PutMapping("/{brandId}")
    public ApiResponse<BrandV1Dto.BrandResponse> update(
        @PathVariable Long brandId,
        @RequestBody BrandAdminV1Dto.UpdateRequest request
    ) {
        return ApiResponse.success(
            BrandV1Dto.BrandResponse.from(brandFacade.update(brandId, request.name(), request.description()))
        );
    }

    @DeleteMapping("/{brandId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> delete(@PathVariable Long brandId) {
        brandFacade.delete(brandId);
        return ResponseEntity.noContent().build();
    }
}
