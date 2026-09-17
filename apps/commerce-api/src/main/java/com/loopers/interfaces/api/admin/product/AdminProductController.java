package com.loopers.interfaces.api.admin.product;

import com.loopers.application.product.AdminProductFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api-admin/v1/products")
public class AdminProductController implements AdminProductApiSpec {

    private final AdminProductFacade adminProductFacade;

    @GetMapping
    @Override
    public ApiResponse<PageResponse<AdminProductDto.ProductResponse>> getProducts(
        @RequestParam(value = "brandId", required = false) Long brandId,
        @RequestParam(value = "page", defaultValue = "0") int page,
        @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        return ApiResponse.success(PageResponse.from(adminProductFacade.getProducts(brandId, page, size), AdminProductDto.ProductResponse::from));
    }

    @GetMapping("/{productId}")
    @Override
    public ApiResponse<AdminProductDto.ProductResponse> getProduct(@PathVariable(value = "productId") Long productId) {
        return ApiResponse.success(AdminProductDto.ProductResponse.from(adminProductFacade.getProduct(productId)));
    }

    @PostMapping
    @Override
    public ApiResponse<AdminProductDto.ProductResponse> register(@RequestBody AdminProductDto.CreateRequest request) {
        return ApiResponse.success(AdminProductDto.ProductResponse.from(
            adminProductFacade.register(request.brandId(), request.name(), request.price(), request.stock())
        ));
    }

    @PutMapping("/{productId}")
    @Override
    public ApiResponse<AdminProductDto.ProductResponse> update(
        @PathVariable(value = "productId") Long productId,
        @RequestBody AdminProductDto.UpdateRequest request
    ) {
        return ApiResponse.success(AdminProductDto.ProductResponse.from(adminProductFacade.update(productId, request.name(), request.price())));
    }

    @PutMapping("/{productId}/stock")
    @Override
    public ApiResponse<AdminProductDto.ProductResponse> changeStock(
        @PathVariable(value = "productId") Long productId,
        @RequestBody AdminProductDto.StockRequest request
    ) {
        return ApiResponse.success(AdminProductDto.ProductResponse.from(adminProductFacade.changeStock(productId, request.stock())));
    }

    @DeleteMapping("/{productId}")
    @Override
    public ApiResponse<Object> delete(@PathVariable(value = "productId") Long productId) {
        adminProductFacade.delete(productId);
        return ApiResponse.success();
    }
}
