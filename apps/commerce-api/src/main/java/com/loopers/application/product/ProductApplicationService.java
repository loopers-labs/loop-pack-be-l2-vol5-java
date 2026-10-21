package com.loopers.application.product;

import com.loopers.application.brand.BrandNotFoundException;
import com.loopers.application.brand.port.BrandRepository;
import com.loopers.application.like.port.LikeRepository;
import com.loopers.application.product.port.ProductListCache;
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
import java.util.OptionalLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ProductApplicationService {
    private final ProductRepository productRepository;
    private final BrandRepository brandRepository;
    private final LikeRepository likeRepository;
    private final ProductListCache productListCache;
    private final ApplicationEventPublisher applicationEventPublisher;

    public ProductApplicationService(ProductRepository productRepository, BrandRepository brandRepository,
        LikeRepository likeRepository, ProductListCache productListCache, ApplicationEventPublisher applicationEventPublisher) {
        this.productRepository = productRepository;
        this.brandRepository = brandRepository;
        this.likeRepository = likeRepository;
        this.productListCache = productListCache;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Transactional(readOnly = true)
    public List<ProductResult> list(int page, int size) {
        return productRepository.findPage(page, size).stream().map(ProductResult::from).toList();
    }

    public ProductResult create(long brandId, String name, long price, int stock) {
        BrandId id = new BrandId(brandId);
        brandRepository.findByIdForUpdate(id).filter(brand -> !brand.isDeleted()).orElseThrow(BrandNotFoundException::new);
        Product product = Product.create(id, name, new Money(price), new Stock(stock));
        ProductResult created = ProductResult.from(productRepository.save(product));
        applicationEventPublisher.publishEvent(new ProductListChangedEvent());
        return created;
    }

    @Transactional(readOnly = true)
    public ProductResult getAdminProduct(long id) {
        return ProductResult.from(productRepository.findById(new ProductId(id)).orElseThrow(ProductNotFoundException::new));
    }

    public ProductResult change(long id, String name, long price) {
        Product product = locked(id);
        product.change(name, new Money(price));
        ProductResult changed = ProductResult.from(productRepository.save(product));
        applicationEventPublisher.publishEvent(new ProductListChangedEvent());
        return changed;
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
        applicationEventPublisher.publishEvent(new ProductListChangedEvent());
    }

    @Transactional(readOnly = true)
    public CustomerProductResult getProduct(long id) {
        Product product = productRepository.findById(new ProductId(id))
            .filter(found -> !found.isDeleted())
            .orElseThrow(ProductNotFoundException::new);
        return compose(List.of(product)).get(0);
    }

    // 목록 cache-aside(설계 19절). 버전을 DB보다 먼저 읽어, commit 전 값을 읽은 느린 요청은 옛 버전 키에만 쓰게 한다.
    // 재고·삭제 여부는 캐시하지 않고 적중 여부와 관계없이 페이지 상품을 PK로 다시 읽어 채운다.
    @Transactional(readOnly = true)
    public List<CustomerProductResult> search(Long brandId, int page, int size, String sort) {
        ProductListQuery query = new ProductListQuery(brandId, sort, page, size);
        OptionalLong version = productListCache.currentVersion();
        List<ProductListItem> items;
        if (version.isEmpty()) {
            items = loadListItems(query);
        } else {
            items = productListCache.find(version.getAsLong(), query).orElseGet(() -> {
                List<ProductListItem> loaded = loadListItems(query);
                productListCache.save(version.getAsLong(), query, loaded);
                return loaded;
            });
        }
        return withCurrentStock(items);
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

    private List<ProductListItem> loadListItems(ProductListQuery query) {
        return compose(productRepository.search(query.brandId(), query.page(), query.size(), query.sort())).stream()
            .map(product -> new ProductListItem(product.id(), product.name(), product.price(), product.brandId(),
                product.brandName(), product.likeCount()))
            .toList();
    }

    private List<CustomerProductResult> withCurrentStock(List<ProductListItem> items) {
        Map<Long, Product> current = productRepository.findAllByIds(items.stream().map(item -> new ProductId(item.id())).toList())
            .stream()
            .collect(Collectors.toMap(product -> product.getId().value(), Function.identity()));
        return items.stream()
            .filter(item -> current.containsKey(item.id()) && !current.get(item.id()).isDeleted())
            .map(item -> new CustomerProductResult(item.id(), item.name(), item.price(),
                current.get(item.id()).getStock().value(), item.brandId(), item.brandName(), item.likeCount()))
            .toList();
    }

    private List<CustomerProductResult> compose(List<Product> found) {
        List<BrandId> brandIds = found.stream().map(Product::getBrandId).distinct().toList();
        Map<BrandId, String> brandNames = brandRepository.findAllByIds(brandIds).stream()
            .collect(Collectors.toMap(Brand::getId, Brand::getName));
        Map<ProductId, Long> likeCounts = productRepository.findLikeCounts(found.stream().map(Product::getId).toList());
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
