package com.loopers.interfaces.api.like;

import com.loopers.application.like.GetLikeFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.product.ProductDto;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class GetLikeController {
    private final GetLikeFacade facade;
    @GetMapping("/api/v1/users/{userId}/likes")
    public ApiResponse<LikeList> get(@RequestHeader(value = "X-USER-ID", required = false) Long requester,
                                    @PathVariable long userId) {
        return ApiResponse.success(new LikeList(facade.get(requester, userId).stream().map(ProductDto.Customer::from).toList()));
    }
    public record LikeList(List<ProductDto.Customer> items) {}
}
