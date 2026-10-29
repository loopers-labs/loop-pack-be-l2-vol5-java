package com.loopers.application.like;

import com.loopers.application.like.port.LikeRepository;
import com.loopers.application.product.ProductNotFoundException;
import com.loopers.application.product.port.ProductRepository;
import com.loopers.domain.like.Like;
import com.loopers.domain.product.ProductId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class LikeApplicationService {
    private final LikeRepository likes;
    private final ProductRepository products;
    public LikeApplicationService(LikeRepository likes, ProductRepository products) {
        this.likes = likes;
        this.products = products;
    }

    public void register(long userId, long productId) {
        ProductId id = new ProductId(productId);
        products.findByIdForUpdate(id).filter(p -> !p.isDeleted()).orElseThrow(ProductNotFoundException::new);
        if (likes.save(new Like(userId, id))) {
            products.adjustLikeCount(id, 1);
        }
    }

    public void cancel(long userId, long productId) {
        ProductId id = new ProductId(productId);
        // 원자적 UPDATE가 상품 행 잠금을 잡으므로 취소 경로에는 별도 잠금을 두지 않는다(설계 18절).
        if (likes.delete(new Like(userId, id))) {
            products.adjustLikeCount(id, -1);
        }
    }
}
