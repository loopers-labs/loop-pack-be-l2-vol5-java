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
        likes.save(new Like(userId, id));
    }

    public void cancel(long userId, long productId) {
        likes.delete(new Like(userId, new ProductId(productId)));
    }
}
