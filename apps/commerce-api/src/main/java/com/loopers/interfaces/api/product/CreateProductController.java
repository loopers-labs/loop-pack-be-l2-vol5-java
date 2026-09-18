package com.loopers.interfaces.api.product;

import com.loopers.application.product.CreateProductFacade;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;

@RestController
@RequiredArgsConstructor
public class CreateProductController {
    private final CreateProductFacade facade;
    @PostMapping("/api-admin/v1/products")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProductDto.Admin> create(@RequestBody ProductDto.Create request) {
        return ApiResponse.success(ProductDto.Admin.from(facade.create(request.brandId(), request.name(), request.price())));
    }
}
