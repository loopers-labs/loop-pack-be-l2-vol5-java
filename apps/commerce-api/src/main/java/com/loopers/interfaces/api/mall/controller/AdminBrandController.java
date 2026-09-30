package com.loopers.interfaces.api.mall.controller;

import com.loopers.application.common.PageCriteria;
import com.loopers.application.common.PageResult;
import com.loopers.application.mall.command.BrandCommand;
import com.loopers.application.mall.query.BrandView;
import com.loopers.application.mall.query.BrandQueryDao;
import com.loopers.application.mall.usecase.CreateBrandUseCase;
import com.loopers.application.mall.usecase.DeleteBrandUseCase;
import com.loopers.application.mall.usecase.UpdateBrandUseCase;
import com.loopers.application.support.error.ApplicationErrorCode;
import com.loopers.application.support.error.ApplicationException;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.mall.dto.BrandApiDto;
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
@RequestMapping("/api-admin/v1/brands")
@RequiredArgsConstructor
// 관리자용 브랜드 CRUD 컨트롤러
public class AdminBrandController {
    private final BrandQueryDao brandQueryDao;
    private final CreateBrandUseCase createBrandUseCase;
    private final UpdateBrandUseCase updateBrandUseCase;
    private final DeleteBrandUseCase deleteBrandUseCase;

    // 브랜드 목록 조회
    @GetMapping
    public ApiResponse<PageResult<BrandView>> findAll(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.success(brandQueryDao.findAll(new PageCriteria(page, size)));
    }

    // 브랜드 단건 조회
    @GetMapping("/{brandId}")
    public ApiResponse<BrandView> find(@PathVariable long brandId) {
        RequestInputValidator.requirePositiveId(brandId, "브랜드 ID");
        return ApiResponse.success(brandQueryDao.findById(brandId).orElseThrow(AdminBrandController::notFound));
    }

    // 브랜드 생성
    @PostMapping
    public ResponseEntity<ApiResponse<BrandApiDto.Response>> create(@RequestBody BrandApiDto.Request request) {
        BrandApiDto.Response response = BrandApiDto.Response.from(createBrandUseCase.execute(request.toCreateCommand()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    // 브랜드 수정
    @PutMapping("/{brandId}")
    public ApiResponse<BrandApiDto.Response> update(@PathVariable long brandId, @RequestBody BrandApiDto.Request request) {
        RequestInputValidator.requirePositiveId(brandId, "브랜드 ID");
        return ApiResponse.success(BrandApiDto.Response.from(updateBrandUseCase.execute(request.toUpdateCommand(brandId))));
    }

    // 브랜드 삭제
    @DeleteMapping("/{brandId}")
    public ApiResponse<Object> delete(@PathVariable long brandId) {
        RequestInputValidator.requirePositiveId(brandId, "브랜드 ID");
        deleteBrandUseCase.execute(new BrandCommand.Delete(brandId));
        return ApiResponse.success();
    }

    private static ApplicationException notFound() {
        return new ApplicationException(ApplicationErrorCode.BRAND_NOT_FOUND);
    }
}
