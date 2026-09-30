package com.loopers.application.product;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.product.ProductSort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// 상품 조회 전담 — 고객·관리자 둘 다 여기서 가져간다. 조회는 같은 이유로 바뀌므로 공유한다.
// (docs/week2/design.md 1번 섹션: 조회 vs 변경 원칙, ProductAdminFacade와 대칭)
@RequiredArgsConstructor
@Component
public class ProductFacade {

    private final ProductService productService;
    private final BrandService brandService;
    private final LikeService likeService;

    public ProductInfo getProduct(Long id) {
        return ProductInfo.from(productService.getProduct(id));
    }

    public List<ProductInfo> getProducts() {
        return productService.getProducts().stream()
            .map(ProductInfo::from)
            .toList();
    }

    /**
     * 고객 상품 상세 — Brand·Like 두 aggregate를 조회해 조합한다.
     * 단건이라 브랜드·좋아요수 모두 단건 조회로 충분하다(일괄조회는 목록에서만 필요).
     */
    public ProductDetailInfo getProductDetail(Long id) {
        ProductModel product = productService.getProduct(id);
        BrandModel brand = brandService.getBrand(product.getBrandId());
        long likeCount = likeService.getLikeCount(id);
        return ProductDetailInfo.of(product, brand, likeCount);
    }

    /**
     * 고객 상품 목록 — 정렬·페이지네이션은 domain(ProductService)이 DB에서 처리하고,
     * 여기(application)에서는 그 결과에 브랜드·좋아요수를 일괄조회로 붙이기만 한다.
     * brandId 일괄조회, likeCount 일괄조회 둘 다 N+1을 피하기 위한 배치 조회다
     * (docs/week2/design.md 3번 섹션).
     */
    public Page<ProductDetailInfo> getProductList(Long brandId, ProductSort sort, Pageable pageable) {
        Page<ProductModel> page = productService.getProducts(brandId, sort, pageable);
        List<ProductModel> products = page.getContent();

        List<Long> brandIds = products.stream().map(ProductModel::getBrandId).distinct().toList();
        Map<Long, BrandModel> brandsById = brandService.getBrandsByIds(brandIds).stream()
            .collect(Collectors.toMap(BrandModel::getId, b -> b));

        List<Long> productIds = products.stream().map(ProductModel::getId).toList();
        Map<Long, Long> likeCounts = likeService.getLikeCounts(productIds);

        List<ProductDetailInfo> infos = products.stream()
            .map(p -> ProductDetailInfo.of(p, brandsById.get(p.getBrandId()), likeCounts.getOrDefault(p.getId(), 0L)))
            .toList();

        return new PageImpl<>(infos, pageable, page.getTotalElements());
    }
}
