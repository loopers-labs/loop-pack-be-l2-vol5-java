package com.loopers.interfaces.api.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.application.product.AdminProductInfo;
import com.loopers.application.product.AdminProductQueryInfo;
import com.loopers.application.product.AdminProductQueryService;
import com.loopers.application.product.AdminProductService;
import com.loopers.domain.common.PageResult;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.commerce.AdminRequester;
import com.loopers.interfaces.api.commerce.InvalidRequestException;
import com.loopers.interfaces.api.commerce.StrictInput;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api-admin/v1/products", produces = "application/json")
public class AdminProductController {
    private final AdminProductService changes;
    private final AdminProductQueryService queries;

    public AdminProductController(AdminProductService changes, AdminProductQueryService queries) {
        this.changes = changes;
        this.queries = queries;
    }

    @PostMapping(consumes = "application/json")
    public ResponseEntity<ApiResponse<AdminProductInfo>> create(
        Authentication requester, @RequestBody JsonNode body) {
        StrictInput.object(body, "brandId", "name", "price", "stockQuantity");
        long brandId = StrictInput.number(body, "brandId");
        long price = StrictInput.number(body, "price");
        int stock = StrictInput.integer(body, "stockQuantity");
        if (brandId <= 0 || price <= 0 || stock < 0) {
            throw new InvalidRequestException();
        }
        String name = StrictInput.text(body, "name");
        return ResponseEntity.status(201).body(ApiResponse.success(changes.create(AdminRequester.role(requester), brandId, name, price, stock)));
    }

    @GetMapping("/{productId}")
    public ApiResponse<AdminProductQueryInfo> detail(
        Authentication requester, @PathVariable String productId) {
        return ApiResponse.success(queries.getDetail(AdminRequester.role(requester), StrictInput.id(productId)));
    }

    @GetMapping
    public ApiResponse<PageResult<AdminProductQueryInfo>> list(
        Authentication requester,
        @RequestParam(required = false) String brandId, @RequestParam(required = false) String page,
        @RequestParam(required = false) String size, @RequestParam(required = false) String sort) {
        return ApiResponse.success(queries.getList(AdminRequester.role(requester), StrictInput.optionalId(brandId), StrictInput.page(page),
            StrictInput.size(size), ProductController.sort(sort)));
    }

    @PutMapping(value = "/{productId}", consumes = "application/json")
    public ApiResponse<AdminProductInfo> update(
        Authentication requester,
        @PathVariable String productId, @RequestBody JsonNode body) {
        long id = StrictInput.id(productId);
        StrictInput.object(body, "name", "price");
        String name = StrictInput.text(body, "name");
        long price = StrictInput.number(body, "price");
        if (price <= 0) {
            throw new InvalidRequestException();
        }
        return ApiResponse.success(changes.update(AdminRequester.role(requester), id, name, price));
    }

    @PutMapping(value = "/{productId}/stock", consumes = "application/json")
    public ApiResponse<StockView> stock(
        Authentication requester,
        @PathVariable String productId, @RequestBody JsonNode body) {
        long id = StrictInput.id(productId);
        StrictInput.object(body, "stockQuantity");
        int quantity = StrictInput.integer(body, "stockQuantity");
        if (quantity < 0) {
            throw new InvalidRequestException();
        }
        var result = changes.changeStock(AdminRequester.role(requester), id, quantity);
        return ApiResponse.success(new StockView(result.productId(), result.stockQuantity()));
    }

    @DeleteMapping("/{productId}")
    public ApiResponse<DeletedProduct> delete(
        Authentication requester, @PathVariable String productId) {
        long id = StrictInput.id(productId);
        changes.delete(AdminRequester.role(requester), id);
        return ApiResponse.success(new DeletedProduct(id, true));
    }

    public record StockView(long productId, int stockQuantity) {
    }

    public record DeletedProduct(long productId, boolean deleted) {
    }
}
