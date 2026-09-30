package com.loopers.product.adapter.in.web;

import com.loopers.product.adapter.in.web.dto.ProductAdminDto;
import com.loopers.product.adapter.in.web.spec.ProductAdminApiSpec;
import com.loopers.product.application.ProductAdminQueryService;
import com.loopers.product.application.port.in.ProductAdminInfo;
import com.loopers.product.application.port.in.ProductCommandUseCase;
import com.loopers.support.web.ApiResponse;
import com.loopers.support.web.PageQuery;
import com.loopers.support.web.PageResponse;
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

import static com.loopers.product.adapter.in.web.dto.ProductAdminDto.required;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api-admin/v1/products")
public class ProductAdminController implements ProductAdminApiSpec {

    private final ProductCommandUseCase productCommandUseCase;
    private final ProductAdminQueryService productAdminQueryService;

    @GetMapping
    @Override
    public ApiResponse<PageResponse<ProductAdminDto.ProductResponse>> getProducts(
        @RequestParam(required = false) Long brandId,
        @RequestParam(required = false) Integer page,
        @RequestParam(required = false) Integer size
    ) {
        Pageable pageable = PageQuery.of(page, size).toPageable(Sort.by(Sort.Direction.DESC, "id"));
        return ApiResponse.success(
            PageResponse.from(productAdminQueryService.getProducts(brandId, pageable), ProductAdminDto.ProductResponse::from)
        );
    }

    @PostMapping
    @Override
    public ApiResponse<ProductAdminDto.ProductResponse> createProduct(@RequestBody ProductAdminDto.CreateRequest request) {
        ProductAdminInfo info = productCommandUseCase.create(
            required(request.brandId(), "brandId"),
            request.name(),
            required(request.price(), "price"),
            required(request.stock(), "stock")
        );
        return ApiResponse.success(ProductAdminDto.ProductResponse.from(info));
    }

    @GetMapping("/{productId}")
    @Override
    public ApiResponse<ProductAdminDto.ProductResponse> getProduct(@PathVariable Long productId) {
        return ApiResponse.success(ProductAdminDto.ProductResponse.from(productAdminQueryService.getProduct(productId)));
    }

    @PutMapping("/{productId}")
    @Override
    public ApiResponse<ProductAdminDto.ProductResponse> updateProduct(
        @PathVariable Long productId,
        @RequestBody ProductAdminDto.UpdateRequest request
    ) {
        ProductAdminInfo info = productCommandUseCase.update(productId, request.name(), required(request.price(), "price"));
        return ApiResponse.success(ProductAdminDto.ProductResponse.from(info));
    }

    @DeleteMapping("/{productId}")
    @Override
    public ApiResponse<Object> deleteProduct(@PathVariable Long productId) {
        productCommandUseCase.delete(productId);
        return ApiResponse.success();
    }

    @PutMapping("/{productId}/stock")
    @Override
    public ApiResponse<ProductAdminDto.ProductResponse> changeStock(
        @PathVariable Long productId,
        @RequestBody ProductAdminDto.StockRequest request
    ) {
        ProductAdminInfo info = productCommandUseCase.changeStock(productId, required(request.stock(), "stock"));
        return ApiResponse.success(ProductAdminDto.ProductResponse.from(info));
    }
}
