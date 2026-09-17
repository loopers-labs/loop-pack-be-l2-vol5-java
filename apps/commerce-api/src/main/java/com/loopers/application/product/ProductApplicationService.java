package com.loopers.application.product;

import com.loopers.application.brand.BrandNotFoundException;
import com.loopers.application.brand.port.BrandRepository;
import com.loopers.application.product.port.ProductRepository;
import com.loopers.domain.brand.BrandId;
import com.loopers.domain.common.Money;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductId;
import com.loopers.domain.product.Stock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ProductApplicationService {
    private final ProductRepository products;
    private final BrandRepository brands;

    private final com.loopers.application.like.port.LikeRepository likes;
    public ProductApplicationService(ProductRepository products, BrandRepository brands,
        com.loopers.application.like.port.LikeRepository likes) {
        this.likes = likes;
        this.products = products;
        this.brands = brands;
    }

    @Transactional(readOnly = true)
    public java.util.List<ProductResult> list(int page, int size) {
        return products.findPage(page, size).stream().map(ProductResult::from).toList();
    }

    public ProductResult create(long brandId, String name, long price, int stock) {
        BrandId id = new BrandId(brandId);
        brands.findByIdForUpdate(id).filter(brand -> !brand.isDeleted()).orElseThrow(BrandNotFoundException::new);
        return ProductResult.from(products.save(Product.create(id, name, new Money(price), new Stock(stock))));
    }

    @Transactional(readOnly = true)
    public ProductResult getAdminProduct(long id) {
        return ProductResult.from(products.findById(new ProductId(id)).orElseThrow(ProductNotFoundException::new));
    }

    public ProductResult change(long id, String name, long price) {
        Product product = locked(id);
        product.change(name, new Money(price));
        return ProductResult.from(products.save(product));
    }

    public ProductResult setStock(long id, int stock) {
        Product product = locked(id);
        product.setStock(new Stock(stock));
        return ProductResult.from(products.save(product));
    }

    public void delete(long id) {
        Product product = locked(id);
        product.delete();
        products.save(product);
    }

    @Transactional(readOnly = true)
    public CustomerProductResult getProduct(long id) {
        Product product = products.findById(new ProductId(id)).filter(p -> !p.isDeleted()).orElseThrow(ProductNotFoundException::new);
        return compose(java.util.List.of(product)).get(0);
    }

    @Transactional(readOnly = true)
    public java.util.List<CustomerProductResult> search(Long brandId, int page, int size, String sort) {
        return compose(products.search(brandId, page, size, sort));
    }

    @Transactional(readOnly = true)
    public java.util.List<CustomerProductResult> myLikes(long requester, long userId, int page, int size) {
        if (requester != userId) { throw new com.loopers.domain.common.RuleViolationException("다른 사용자의 좋아요는 조회할 수 없습니다."); }
        var ids = likes.findActiveProductIds(userId, page, size);
        return compose(products.findAllByIds(ids).stream().filter(p -> !p.isDeleted())
            .sorted(java.util.Comparator.comparingLong((Product p) -> p.getId().value()).reversed()).toList());
    }

    private java.util.List<CustomerProductResult> compose(java.util.List<Product> found) {
        var names = brands.findAllByIds(found.stream().map(Product::getBrandId).distinct().toList()).stream()
            .collect(java.util.stream.Collectors.toMap(b -> b.getId(), b -> b.getName()));
        var counts = likes.countByProductIds(found.stream().map(Product::getId).toList());
        return found.stream().map(p -> new CustomerProductResult(p.getId().value(), p.getName(), p.getPrice().value(),
            p.getStock().value(), p.getBrandId().value(), names.get(p.getBrandId()), counts.getOrDefault(p.getId(), 0L))).toList();
    }

    private Product locked(long id) {
        return products.findByIdForUpdate(new ProductId(id)).orElseThrow(ProductNotFoundException::new);
    }
}
