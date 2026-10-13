package com.loopers.application.product;

import com.loopers.domain.brand.BrandService;
import com.loopers.domain.common.PageNumber;
import com.loopers.domain.common.PageSize;
import com.loopers.domain.common.PageWindow;
import com.loopers.domain.common.Quantity;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductAdminQuery;
import com.loopers.domain.product.ProductListingCache;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.product.ProductViewQuery;
import com.loopers.support.error.DomainError;
import com.loopers.support.error.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

@Component
@RequiredArgsConstructor
public class ProductFacade {

    private final BrandService brandService;
    private final ProductService productService;
    private final ProductViewQuery productViewQuery;
    private final ProductAdminQuery productAdminQuery;
    private final ProductListingCache productListingCache;

    @Transactional
    public Product register(Long brandId, String name, Price price) {
        brandService.requireAvailable(brandId);
        return productService.register(brandId, name, price);
    }

    @Transactional
    public Product update(Long productId, String name, Price price) {
        return productService.update(productId, name, price);
    }

    @Transactional
    public void delete(Long productId) {
        productService.delete(productId);
    }

    @Transactional
    public Product adjustStock(Long productId, Quantity quantity) {
        return productService.adjustStock(productId, quantity);
    }

    public PageWindow<ProductViewQuery.View> findPage(ProductViewQuery.Criteria criteria) {
        if (!isCachedPage(criteria)) {
            return productViewQuery.findPage(criteria);
        }
        return withLikes(listingOf(criteria), criteria.viewerId());
    }

    public ProductViewQuery.View get(Long productId, Long viewerId) {
        return productViewQuery.findAliveById(productId, viewerId)
            .orElseThrow(() -> new DomainException(DomainError.PRODUCT_NOT_FOUND));
    }

    public PageWindow<ProductAdminQuery.View> findPageForAdmin(
        Long brandId, ProductAdminQuery.Sort sort, PageNumber page, PageSize size) {
        return productAdminQuery.findPage(brandId, sort, page, size);
    }

    public Product getForAdmin(Long productId) {
        return productService.get(productId);
    }

    private static boolean isCachedPage(ProductViewQuery.Criteria criteria) {
        return criteria.sort() != ProductViewQuery.Sort.LIKES_DESC
            && criteria.page().equals(PageNumber.FIRST)
            && criteria.size().equals(PageSize.DEFAULT);
    }

    private PageWindow<ProductViewQuery.Listing> listingOf(ProductViewQuery.Criteria criteria) {
        OptionalLong generation = productListingCache.generation(criteria.brandId());
        if (generation.isEmpty()) {
            return productViewQuery.findListingPage(criteria);
        }
        return productListingCache.find(criteria.brandId(), criteria.sort(), generation.getAsLong())
            .orElseGet(() -> {
                PageWindow<ProductViewQuery.Listing> loaded = productViewQuery.findListingPage(criteria);
                productListingCache.save(criteria.brandId(), criteria.sort(), generation.getAsLong(), loaded);
                return loaded;
            });
    }

    private PageWindow<ProductViewQuery.View> withLikes(PageWindow<ProductViewQuery.Listing> listing, Long viewerId) {
        List<Long> ids = listing.items().stream().map(ProductViewQuery.Listing::id).toList();
        Map<Long, ProductViewQuery.Likes> likes = productViewQuery.findLikesOfAlive(ids, viewerId);
        List<ProductViewQuery.View> items = listing.items().stream()
            .filter(item -> likes.containsKey(item.id()))
            .map(item -> item.with(likes.get(item.id())))
            .toList();
        return new PageWindow<>(items, listing.hasNext());
    }
}
