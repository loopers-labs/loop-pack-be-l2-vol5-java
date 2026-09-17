package com.loopers.interfaces.api.brand;

import com.loopers.application.brand.BrandApplicationService;
import com.loopers.application.brand.BrandNotFoundException;
import com.loopers.application.brand.BrandResult;
import com.loopers.application.user.UserIdentificationService;
import com.loopers.domain.brand.BrandId;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/brands")
public class BrandController {
    private final BrandApplicationService service;
    private final UserIdentificationService users;

    public BrandController(BrandApplicationService service, UserIdentificationService users) {
        this.service = service;
        this.users = users;
    }

    @GetMapping("/{brandId}")
    public ApiResponse<DetailResponse> get(@PathVariable long brandId,
        @RequestHeader(value = "X-USER-ID", required = false) String userId) {
        if (userId == null) {
            throw new CoreException(ErrorType.UNAUTHORIZED);
        }
        long id;
        try {
            id = Long.parseLong(userId);
        } catch (NumberFormatException e) {
            throw new CoreException(ErrorType.BAD_REQUEST);
        }
        if (!users.exists(id)) {
            throw new CoreException(ErrorType.UNAUTHORIZED);
        }
        BrandResult result = service.getBrand(new BrandId(brandId));
        return ApiResponse.success(new DetailResponse(result.id().value(), result.name()));
    }

    @ExceptionHandler(BrandNotFoundException.class)
    public ResponseEntity<ApiResponse<Object>> notFound(BrandNotFoundException exception) {
        return ResponseEntity.status(404)
            .body(ApiResponse.fail(ErrorType.NOT_FOUND.getCode(), exception.getMessage()));
    }

    public record DetailResponse(long id, String name) {
    }
}
