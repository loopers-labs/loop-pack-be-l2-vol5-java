package com.loopers.interfaces.api.brand;

import com.loopers.application.brand.CreateBrandFacade;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;

@RestController
@RequiredArgsConstructor
public class CreateBrandController {
    private final CreateBrandFacade facade;
    @PostMapping("/api-admin/v1/brands")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<BrandDto.Admin> create(@RequestBody BrandDto.Request request) {
        return ApiResponse.success(BrandDto.Admin.from(facade.create(request.name())));
    }
}
