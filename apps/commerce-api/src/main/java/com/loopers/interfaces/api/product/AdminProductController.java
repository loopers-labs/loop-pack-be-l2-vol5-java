package com.loopers.interfaces.api.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.application.product.ProductApplicationService;
import com.loopers.application.product.ProductResult;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.RequestValues;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
@RequestMapping("/api-admin/v1/products")
public class AdminProductController {
    private final ProductApplicationService service;
    public AdminProductController(ProductApplicationService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProductResult> create(@RequestBody CreateRequest request) {
        return ApiResponse.success(service.create(RequestValues.integer(request.brandId()), RequestValues.name(request.name()),
            RequestValues.integer(request.price()), RequestValues.stock(request.stock())));
    }

    @GetMapping("/{id}")
    public ApiResponse<ProductResult> get(@PathVariable long id) { return ApiResponse.success(service.getAdminProduct(id)); }

    @GetMapping
    public ApiResponse<List<ProductResult>> list(@RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
        RequestValues.page(page, size);
        return ApiResponse.success(service.list(page, size));
    }

    @PutMapping("/{id}")
    public ApiResponse<ProductResult> change(@PathVariable long id, @RequestBody ChangeRequest request) {
        return ApiResponse.success(service.change(id, RequestValues.name(request.name()), RequestValues.integer(request.price())));
    }

    @PutMapping("/{id}/stock")
    public ApiResponse<ProductResult> stock(@PathVariable long id, @RequestBody StockRequest request) {
        return ApiResponse.success(service.setStock(id, RequestValues.stock(request.stock())));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Object> delete(@PathVariable long id) { service.delete(id); return ApiResponse.success(); }

    public record CreateRequest(JsonNode brandId, JsonNode name, JsonNode price, JsonNode stock) { }
    public record ChangeRequest(JsonNode name, JsonNode price) { }
    public record StockRequest(JsonNode stock) { }
}
