package com.loopers.interfaces.api.admin.product;

import com.loopers.application.product.ProductAdminFacade;
import com.loopers.application.product.ProductAdminInfo;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.support.PageQuery;
import com.loopers.interfaces.api.support.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.loopers.interfaces.api.admin.product.ProductAdminV1Dto.required;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api-admin/v1/products")
public class ProductAdminV1Controller implements ProductAdminV1ApiSpec {

    private final ProductAdminFacade productAdminFacade;

    @GetMapping
    @Override
    public ApiResponse<PageResponse<ProductAdminV1Dto.ProductResponse>> getProducts(
        @RequestParam(required = false) Long brandId,
        @RequestParam(required = false) Integer page,
        @RequestParam(required = false) Integer size
    ) {
        Pageable pageable = PageQuery.of(page, size).toPageable(Sort.by(Sort.Direction.DESC, "id"));
        return ApiResponse.success(
            PageResponse.from(productAdminFacade.getProducts(brandId, pageable), ProductAdminV1Dto.ProductResponse::from)
        );
    }

    @PostMapping
    @Override
    public ApiResponse<ProductAdminV1Dto.ProductResponse> createProduct(@RequestBody ProductAdminV1Dto.CreateRequest request) {
        ProductAdminInfo info = productAdminFacade.create(
            required(request.brandId(), "brandId"),
            request.name(),
            required(request.price(), "price"),
            required(request.stock(), "stock")
        );
        return ApiResponse.success(ProductAdminV1Dto.ProductResponse.from(info));
    }

    @GetMapping("/{productId}")
    @Override
    public ApiResponse<ProductAdminV1Dto.ProductResponse> getProduct(@PathVariable Long productId) {
        return ApiResponse.success(ProductAdminV1Dto.ProductResponse.from(productAdminFacade.getProduct(productId)));
    }

    @PutMapping("/{productId}")
    @Override
    public ApiResponse<ProductAdminV1Dto.ProductResponse> updateProduct(
        @PathVariable Long productId,
        @RequestBody ProductAdminV1Dto.UpdateRequest request
    ) {
        ProductAdminInfo info = productAdminFacade.update(productId, request.name(), required(request.price(), "price"));
        return ApiResponse.success(ProductAdminV1Dto.ProductResponse.from(info));
    }

    @DeleteMapping("/{productId}")
    @Override
    public ApiResponse<Object> deleteProduct(@PathVariable Long productId) {
        productAdminFacade.delete(productId);
        return ApiResponse.success();
    }

    @PutMapping("/{productId}/stock")
    @Override
    public ApiResponse<ProductAdminV1Dto.ProductResponse> changeStock(
        @PathVariable Long productId,
        @RequestBody ProductAdminV1Dto.StockRequest request
    ) {
        ProductAdminInfo info = productAdminFacade.changeStock(productId, required(request.stock(), "stock"));
        return ApiResponse.success(ProductAdminV1Dto.ProductResponse.from(info));
    }
}
