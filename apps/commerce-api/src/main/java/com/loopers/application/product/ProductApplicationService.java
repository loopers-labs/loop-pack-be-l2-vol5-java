package com.loopers.application.product;

import com.loopers.application.brand.BrandNotFoundException;
import com.loopers.application.brand.port.BrandRepository;
import com.loopers.application.like.port.LikeRepository;
import com.loopers.application.product.port.ProductRepository;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandId;
import com.loopers.domain.common.Money;
import com.loopers.domain.common.RuleViolationException;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductId;
import com.loopers.domain.product.Stock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ProductApplicationService {
    private final ProductRepository productRepository;
    private final BrandRepository brandRepository;
    private final LikeRepository likeRepository;

    public ProductApplicationService(ProductRepository productRepository, BrandRepository brandRepository,
        LikeRepository likeRepository) {
        this.productRepository = productRepository;
        this.brandRepository = brandRepository;
        this.likeRepository = likeRepository;
    }

    @Transactional(readOnly = true)
    public List<ProductResult> list(int page, int size) {
        return productRepository.findPage(page, size).stream().map(ProductResult::from).toList();
    }

    public ProductResult create(long brandId, String name, long price, int stock) {
        BrandId id = new BrandId(brandId);
        brandRepository.findByIdForUpdate(id).filter(brand -> !brand.isDeleted()).orElseThrow(BrandNotFoundException::new);
        return ProductResult.from(productRepository.save(Product.create(id, name, new Money(price), new Stock(stock))));
    }

    @Transactional(readOnly = true)
    public ProductResult getAdminProduct(long id) {
        return ProductResult.from(productRepository.findById(new ProductId(id)).orElseThrow(ProductNotFoundException::new));
    }

    public ProductResult change(long id, String name, long price) {
        Product product = locked(id);
        product.change(name, new Money(price));
        return ProductResult.from(productRepository.save(product));
    }

    public ProductResult setStock(long id, int stock) {
        Product product = locked(id);
        product.setStock(new Stock(stock));
        return ProductResult.from(productRepository.save(product));
    }

    public void delete(long id) {
        Product product = locked(id);
        product.delete();
        productRepository.save(product);
    }

    @Transactional(readOnly = true)
    public CustomerProductResult getProduct(long id) {
        Product product = productRepository.findById(new ProductId(id))
            .filter(found -> !found.isDeleted())
            .orElseThrow(ProductNotFoundException::new);
        return compose(List.of(product)).get(0);
    }

    @Transactional(readOnly = true)
    public List<CustomerProductResult> search(Long brandId, int page, int size, String sort) {
        return compose(productRepository.search(brandId, page, size, sort));
    }

    @Transactional(readOnly = true)
    public List<CustomerProductResult> myLikes(long requester, long userId, int page, int size) {
        if (requester != userId) {
            throw new RuleViolationException("다른 사용자의 좋아요는 조회할 수 없습니다.");
        }
        List<ProductId> likedProductIds = likeRepository.findActiveProductIds(userId, page, size);
        return compose(productRepository.findAllByIds(likedProductIds).stream()
            .filter(product -> !product.isDeleted())
            .sorted(Comparator.comparingLong((Product product) -> product.getId().value()).reversed())
            .toList());
    }

    private List<CustomerProductResult> compose(List<Product> found) {
        List<BrandId> brandIds = found.stream().map(Product::getBrandId).distinct().toList();
        Map<BrandId, String> brandNames = brandRepository.findAllByIds(brandIds).stream()
            .collect(Collectors.toMap(Brand::getId, Brand::getName));
        Map<ProductId, Long> likeCounts = likeRepository.countByProductIds(found.stream().map(Product::getId).toList());
        return found.stream()
            .map(product -> new CustomerProductResult(product.getId().value(), product.getName(), product.getPrice().value(),
                product.getStock().value(), product.getBrandId().value(), brandNames.get(product.getBrandId()),
                likeCounts.getOrDefault(product.getId(), 0L)))
            .toList();
    }

    private Product locked(long id) {
        return productRepository.findByIdForUpdate(new ProductId(id)).orElseThrow(ProductNotFoundException::new);
    }
}
