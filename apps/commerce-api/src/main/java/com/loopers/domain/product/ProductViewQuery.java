package com.loopers.domain.product;

import com.loopers.domain.common.PageNumber;
import com.loopers.domain.common.PageSize;
import com.loopers.domain.common.PageWindow;

import java.util.List;
import java.util.Optional;

public interface ProductViewQuery {

    Optional<View> findAliveById(Long productId, Long viewerId);

    PageWindow<View> findPage(Criteria criteria);

    List<View> findLikedBy(Long userId, PageNumber page, PageSize size);

    record View(
        Long id,
        String name,
        long price,
        boolean soldOut,
        Long brandId,
        String brandName,
        long likeCount,
        boolean liked
    ) {}

    record Criteria(Long brandId, Sort sort, PageNumber page, PageSize size, Long viewerId) {
        public Criteria {
            if (page == null) {
                page = PageNumber.FIRST;
            }
            if (sort == null) {
                sort = Sort.LATEST;
            }
            if (size == null) {
                size = PageSize.DEFAULT;
            }
        }

        public int offset() {
            return page.offsetWith(size);
        }
    }

    enum Sort {
        LATEST,
        PRICE_ASC,
        LIKES_DESC
    }
}
