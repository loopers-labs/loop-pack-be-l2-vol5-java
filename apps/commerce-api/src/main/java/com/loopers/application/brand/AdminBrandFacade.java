package com.loopers.application.brand;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductService;
import com.loopers.support.paging.PageRequests;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 관리자용 브랜드 유스케이스.
 */
@RequiredArgsConstructor
@Component
public class AdminBrandFacade {

    private final BrandService brandService;
    private final ProductService productService;
    private final LikeService likeService;

    @Transactional
    public BrandInfo create(String name) {
        return BrandInfo.from(brandService.create(name));
    }

    @Transactional
    public BrandInfo update(Long brandId, String name) {
        // 변경 감지가 모든 컬럼을 쓰므로, 처음 읽을 때부터 잠가서 동시에 삭제된 브랜드를 되살리지 않는다 (D-24).
        Brand brand = brandService.getForUpdate(brandId);
        brand.update(name);
        return BrandInfo.from(brand);
    }

    @Transactional
    public void delete(Long brandId) {
        // 브랜드를 먼저, 상품을 id 오름차순으로 잠근다 (전역 잠금 순서, D-27). 연결 상품은 재고 0이어도 함께 삭제한다.
        // 상품은 다른 Aggregate 라 여기서 조합한다. 예외를 잡지 않으므로 중간에 실패하면 전체가 rollback 된다 (D-23).
        Brand brand = brandService.getForUpdate(brandId);
        List<Product> products = productService.getAllActiveByBrandForUpdate(brandId);
        products.forEach(Product::delete);
        // 삭제한 상품의 좋아요 수 집계 행을 지워 브랜드 없는 좋아요순 목록에서 뺀다 (D-33).
        likeService.removeCounts(products.stream().map(Product::getId).toList());
        brand.delete();
    }

    @Transactional(readOnly = true)
    public BrandInfo get(Long brandId) {
        return BrandInfo.from(brandService.get(brandId));
    }

    @Transactional(readOnly = true)
    public Page<BrandInfo> getList(int page, int size) {
        return brandService.getList(PageRequests.of(page, size)).map(BrandInfo::from);
    }
}
