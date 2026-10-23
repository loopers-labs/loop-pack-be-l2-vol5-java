package com.loopers.application.product.cache;

import com.loopers.application.product.dto.ProductListItemInfo;
import com.loopers.application.product.dto.ProductListQuery;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * 상품 목록 캐시에 담는 값. 고객 목록 항목({@link ProductListItemInfo})에서 좋아요 수를 뺀 나머지와 전체 개수다.
 * 페이지 번호·크기는 캐시 키({@link ProductListQuery})에 있으므로 담지 않는다.
 *
 * <p>좋아요 수는 가장 잦은 쓰기라 담지 않는다. 캐시를 쓸 때마다 카운터 테이블에서 읽어 합친다.
 */
public record CachedProductListInfo(List<Item> items, long totalElements) {

    public CachedProductListInfo {
        // 깨진 값(items 누락)을 정상 목록으로 읽지 않도록 만들 때 거절한다.
        items = List.copyOf(items);
    }

    public record Item(Long id, String name, long price, ProductListItemInfo.BrandSummary brand) {

        public ProductListItemInfo withLikeCount(long likeCount) {
            return new ProductListItemInfo(id, name, price, brand, likeCount);
        }
    }

    public static CachedProductListInfo from(Page<ProductListItemInfo> page) {
        List<Item> items = page.getContent().stream()
            .map(info -> new Item(info.id(), info.name(), info.price(), info.brand()))
            .toList();
        return new CachedProductListInfo(items, page.getTotalElements());
    }
}
