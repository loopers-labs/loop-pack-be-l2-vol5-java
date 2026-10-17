package com.loopers.support.fixture;

import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import org.springframework.stereotype.Component;

/**
 * 테스트에서 노출 상품을 만든다. 상품 등록 유스케이스처럼 상품을 저장하고 좋아요 수 집계 행도 함께 만든다.
 * 집계 행이 없으면 좋아요 증가가 반영되지 않고 브랜드 없는 좋아요순 목록에도 나오지 않는다.
 * 삭제 상품은 집계 행이 없어야 하므로 이 헬퍼를 쓰지 않고 저장소로 직접 저장한다.
 */
@Component
public class ExposedProducts {
    private final ProductRepository productRepository;
    private final LikeService likeService;

    public ExposedProducts(ProductRepository productRepository, LikeService likeService) {
        this.productRepository = productRepository;
        this.likeService = likeService;
    }

    public Product save(Product product) {
        Product saved = productRepository.save(product);
        likeService.createCount(saved.getId());
        return saved;
    }
}
