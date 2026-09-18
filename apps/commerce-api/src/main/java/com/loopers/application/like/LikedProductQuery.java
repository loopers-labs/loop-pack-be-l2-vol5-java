package com.loopers.application.like;

import com.loopers.application.product.query.ProductView;
import java.util.List;

public interface LikedProductQuery {
    List<ProductView> findByUser(long userId);
}
