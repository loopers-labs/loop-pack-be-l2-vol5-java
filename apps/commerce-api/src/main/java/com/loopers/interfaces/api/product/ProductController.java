package com.loopers.interfaces.api.product;

import com.loopers.application.like.LikeApplicationService;
import com.loopers.application.product.CustomerProductResult;
import com.loopers.application.product.ProductApplicationService;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.CustomerIdentity;
import com.loopers.interfaces.api.RequestValues;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
public class ProductController {
    private final ProductApplicationService products;
    private final LikeApplicationService likes;
    private final CustomerIdentity identity;
    public ProductController(ProductApplicationService products, LikeApplicationService likes, CustomerIdentity identity) {
        this.products = products;
        this.likes = likes;
        this.identity = identity;
    }

    @GetMapping("/api/v1/products/{id}")
    public ApiResponse<CustomerProductResult> get(@PathVariable long id,
        @RequestHeader(value = "X-USER-ID", required = false) String user) {
        identity.require(user);
        return ApiResponse.success(products.getProduct(id));
    }

    @GetMapping("/api/v1/products")
    public ApiResponse<List<CustomerProductResult>> list(@RequestHeader(value = "X-USER-ID", required = false) String user,
        @RequestParam(required = false) Long brandId, @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size, @RequestParam(defaultValue = "latest") String sort) {
        identity.require(user);
        RequestValues.page(page, size);
        if (!List.of("latest", "price_asc", "likes_desc").contains(sort) || (brandId != null && brandId <= 0)) {
            throw new CoreException(ErrorType.BAD_REQUEST);
        }
        return ApiResponse.success(products.search(brandId, page, size, sort));
    }

    @PostMapping("/api/v1/products/{id}/likes")
    public ApiResponse<Object> register(@PathVariable long id,
        @RequestHeader(value = "X-USER-ID", required = false) String user) {
        likes.register(identity.require(user), id);
        return ApiResponse.success();
    }

    @DeleteMapping("/api/v1/products/{id}/likes")
    public ApiResponse<Object> cancel(@PathVariable long id,
        @RequestHeader(value = "X-USER-ID", required = false) String user) {
        likes.cancel(identity.require(user), id);
        return ApiResponse.success();
    }

    @GetMapping("/api/v1/users/{userId}/likes")
    public ApiResponse<List<CustomerProductResult>> myLikes(@PathVariable long userId,
        @RequestHeader(value = "X-USER-ID", required = false) String user,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        RequestValues.page(page, size);
        return ApiResponse.success(products.myLikes(identity.require(user), userId, page, size));
    }
}
