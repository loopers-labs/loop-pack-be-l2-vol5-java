package com.loopers.application.product;

import com.loopers.application.product.cache.ProductListChangedEvent;
import com.loopers.application.product.dto.ProductInfo;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.common.Money;
import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.product.Stock;
import com.loopers.support.paging.PageRequests;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 관리자용 상품 유스케이스.
 */
@RequiredArgsConstructor
@Component
public class AdminProductFacade {

    private final BrandService brandService;
    private final ProductService productService;
    private final LikeService likeService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public ProductInfo create(Long brandId, String name, long price, int stock) {
        // 브랜드는 다른 Aggregate 라 존재 여부를 여기서 확인한다. 상품은 brandId 만 가진다.
        // 공유 잠금으로 읽어 브랜드 삭제(배타 잠금)와 차례로 처리한다. 등록끼리는 서로 기다리지 않는다.
        brandService.getForShare(brandId);
        Product product = productService.create(brandId, name, new Money(price), new Stock(stock));
        // 좋아요 수 집계 행이 있어야 브랜드 없는 좋아요순 목록에 나온다.
        likeService.createCount(product.getId());
        eventPublisher.publishEvent(new ProductListChangedEvent());
        return ProductInfo.from(product);
    }

    @Transactional
    public ProductInfo update(Long productId, String name, long price) {
        // 변경 감지가 모든 컬럼을 쓰므로, 처음 읽을 때부터 잠가서 확정이 차감한 재고나 삭제 표시를 낡은 값으로 덮지 않는다.
        Product product = productService.getForUpdate(productId);
        product.update(name, new Money(price));
        eventPublisher.publishEvent(new ProductListChangedEvent());
        return ProductInfo.from(product);
    }

    // 재고는 고객 목록에 없어 목록 캐시를 비우지 않는다.
    @Transactional
    public ProductInfo changeStock(Long productId, int stock) {
        Product product = productService.getForUpdate(productId);
        product.changeStock(stock);
        return ProductInfo.from(product);
    }

    @Transactional
    public void delete(Long productId) {
        productService.getForUpdate(productId).delete();
        likeService.removeCounts(List.of(productId));
        eventPublisher.publishEvent(new ProductListChangedEvent());
    }

    @Transactional(readOnly = true)
    public ProductInfo get(Long productId) {
        return ProductInfo.from(productService.get(productId));
    }

    @Transactional(readOnly = true)
    public Page<ProductInfo> getList(int page, int size) {
        return productService.getList(PageRequests.of(page, size)).map(ProductInfo::from);
    }
}
