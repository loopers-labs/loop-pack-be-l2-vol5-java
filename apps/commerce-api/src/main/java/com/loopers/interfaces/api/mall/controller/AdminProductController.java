package com.loopers.interfaces.api.mall.controller;

import com.loopers.application.common.PageCriteria;
import com.loopers.application.common.PageResult;
import com.loopers.application.mall.query.AdminProductView;
import com.loopers.application.mall.usecase.CreateProductUseCase;
import com.loopers.application.mall.usecase.DeleteProductUseCase;
import com.loopers.application.mall.command.ProductCommand;
import com.loopers.application.mall.query.ProductCriteria;
import com.loopers.application.mall.query.ProductQueryDao;
import com.loopers.application.mall.query.ProductSort;
import com.loopers.application.mall.usecase.SetProductStockUseCase;
import com.loopers.application.mall.usecase.UpdateProductUseCase;
import com.loopers.application.support.error.ApplicationErrorCode;
import com.loopers.application.support.error.ApplicationException;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.mall.dto.ProductApiDto;
import com.loopers.interfaces.api.support.RequestInputValidator;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api-admin/v1/products")
@RequiredArgsConstructor
// 관리자용 상품 CRUD 컨트롤러
public class AdminProductController {
    private final ProductQueryDao productQueryDao;
    private final CreateProductUseCase createProductUseCase;
    private final UpdateProductUseCase updateProductUseCase;
    private final DeleteProductUseCase deleteProductUseCase;
    private final SetProductStockUseCase setProductStockUseCase;

    // 상품 목록 조회
    @GetMapping
    public ApiResponse<PageResult<AdminProductView>> findAll(
        @RequestParam(required = false) Long brandId,
        @RequestParam(defaultValue = "latest") String sort,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        ProductCriteria criteria = new ProductCriteria(brandId, ProductSort.from(sort), new PageCriteria(page, size));
        return ApiResponse.success(productQueryDao.findAdminProducts(criteria));
    }

    // 상품 단건 조회
    @GetMapping("/{productId}")
    public ApiResponse<AdminProductView> find(@PathVariable long productId) {
        RequestInputValidator.requirePositiveId(productId, "상품 ID");
        return ApiResponse.success(productQueryDao.findAdminProduct(productId).orElseThrow(AdminProductController::notFound));
    }

    // 상품 생성
    @PostMapping
    public ResponseEntity<ApiResponse<AdminProductView>> create(@RequestBody ProductApiDto.CreateRequest request) {
        AdminProductView product = findView(createProductUseCase.execute(request.toCommand()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(product));
    }

    // 상품 수정
    @PutMapping("/{productId}")
    public ApiResponse<AdminProductView> update(@PathVariable long productId, @RequestBody ProductApiDto.UpdateRequest request) {
        RequestInputValidator.requirePositiveId(productId, "상품 ID");
        return ApiResponse.success(findView(updateProductUseCase.execute(request.toCommand(productId))));
    }

    // 상품 삭제
    @DeleteMapping("/{productId}")
    public ApiResponse<Object> delete(@PathVariable long productId) {
        RequestInputValidator.requirePositiveId(productId, "상품 ID");
        deleteProductUseCase.execute(new ProductCommand.Delete(productId));
        return ApiResponse.success();
    }

    // 상품 재고 설정
    @PutMapping("/{productId}/stock")
    public ApiResponse<AdminProductView> setStock(@PathVariable long productId, @RequestBody ProductApiDto.StockRequest request) {
        RequestInputValidator.requirePositiveId(productId, "상품 ID");
        return ApiResponse.success(findView(setProductStockUseCase.execute(request.toCommand(productId))));
    }

    private AdminProductView findView(long productId) {
        return productQueryDao.findAdminProduct(productId).orElseThrow(AdminProductController::notFound);
    }

    private static ApplicationException notFound() {
        return new ApplicationException(ApplicationErrorCode.PRODUCT_NOT_FOUND);
    }
}
