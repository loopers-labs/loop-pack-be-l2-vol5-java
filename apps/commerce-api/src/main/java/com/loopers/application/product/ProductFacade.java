package com.loopers.application.product;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.like.LikeRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.ProductSort;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

@Slf4j
@RequiredArgsConstructor
@Component
public class ProductFacade {

    private final BrandRepository brandRepository;
    private final LikeRepository likeRepository;
    private final ProductRepository productRepository;
    private final CustomerProductListReader customerProductListReader;
    private final ProductListCache productListCache;
    private final ProductListCacheInvalidator productListCacheInvalidator;

    @Transactional(readOnly = true)
    public ProductInfo getDetail(Long productId) {
        Product product = productRepository.findById(productId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다."));
        return ProductInfo.from(product);
    }

    @Transactional(readOnly = true)
    public CustomerProductInfo getCustomerDetail(Long productId) {
        Product product = findActiveProductById(productId);
        Brand brand = findActiveBrandById(product.getBrandId());
        long likeCount = likeRepository.countByProductId(productId);
        return CustomerProductInfo.from(product, brand, likeCount);
    }

    // hit에서는 DB 커넥션을 잡지 않도록 트랜잭션은 miss의 DB 조회에만 연다.
    public List<CustomerProductInfo> getCustomerList(Long brandId, int page, int size, String sort) {
        if (page < 0 || size < 1 || size > 100) {
            throw new CoreException(ErrorType.BAD_REQUEST, "페이지 입력이 올바르지 않습니다.");
        }
        ProductSort productSort = customerSort(sort);
        // 조회를 시작할 때 읽은 버전의 키에만 저장해, 늦게 끝난 조회가 새 버전의 결과를 덮지 않게 한다.
        OptionalLong version = readCacheVersion(brandId);
        if (version.isEmpty()) {
            return customerProductListReader.read(brandId, productSort, page, size);
        }
        ProductListCacheKey key = new ProductListCacheKey(brandId, productSort, page, size, version.getAsLong());
        return findCachedList(key).filter(this::containsOnlyActiveProducts).orElseGet(() -> {
            List<CustomerProductInfo> products = customerProductListReader.read(brandId, productSort, page, size);
            storeCachedList(key, products);
            return products;
        });
    }

    // 캐시는 보조 수단이므로 Redis 오류는 DB 조회로 우회하고 응답에 전파하지 않는다.
    private OptionalLong readCacheVersion(Long brandId) {
        try {
            return OptionalLong.of(productListCache.currentVersion(brandId));
        } catch (RuntimeException exception) {
            log.warn("상품 목록 캐시 버전을 읽지 못해 DB에서 조회합니다. brandId={}, cause={}", brandId, exception.toString());
            return OptionalLong.empty();
        }
    }

    private Optional<List<CustomerProductInfo>> findCachedList(ProductListCacheKey key) {
        try {
            return productListCache.find(key);
        } catch (RuntimeException exception) {
            log.warn("상품 목록 캐시를 읽지 못해 DB에서 조회합니다. key={}, cause={}", key.value(), exception.toString());
            return Optional.empty();
        }
    }

    private void storeCachedList(ProductListCacheKey key, List<CustomerProductInfo> products) {
        try {
            productListCache.put(key, products);
        } catch (RuntimeException exception) {
            log.warn("상품 목록 캐시를 저장하지 못했습니다. key={}, cause={}", key.value(), exception.toString());
        }
    }

    // 무효화가 누락돼도 삭제된 상품을 노출하지 않도록 hit 결과의 삭제 여부는 DB에서 확인한다.
    private boolean containsOnlyActiveProducts(List<CustomerProductInfo> products) {
        if (products.isEmpty()) {
            return true;
        }
        List<Long> productIds = products.stream().map(CustomerProductInfo::id).toList();
        return productRepository.countActiveByIds(productIds) == productIds.size();
    }

    private ProductSort customerSort(String sort) {
        if (sort == null || sort.isBlank() || "latest".equals(sort)) {
            return ProductSort.LATEST;
        }
        return switch (sort) {
            case "price_asc" -> ProductSort.PRICE_ASC;
            case "likes_desc" -> ProductSort.LIKES_DESC;
            default -> throw new CoreException(ErrorType.BAD_REQUEST, "정렬 입력이 올바르지 않습니다.");
        };
    }

    @Transactional(readOnly = true)
    public List<ProductInfo> getList(ProductListStatus status) {
        List<Product> products = switch (status) {
            case ACTIVE -> productRepository.findAllActive();
            case DELETED -> productRepository.findAllDeleted();
            case ALL -> productRepository.findAll();
        };
        return products.stream().map(ProductInfo::from).toList();
    }

    @Transactional
    public ProductInfo register(Long brandId, String name, long price) {
        Brand brand = findActiveBrandByIdWithSharedLock(brandId);
        Product product = Product.create(brand.getId(), name, price);

        Product savedProduct = productRepository.save(product);
        productListCacheInvalidator.invalidateAfterCommit(savedProduct.getBrandId());
        return ProductInfo.from(savedProduct);
    }

    @Transactional
    public ProductInfo changeStock(Long productId, long quantity) {
        Product product = findActiveProductById(productId);
        product.changeStockTo(quantity);

        if (productRepository.updateActiveStock(productId, quantity, ZonedDateTime.now()) == 0) {
            throw new CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다.");
        }
        return ProductInfo.from(product);
    }

    @Transactional
    public ProductInfo update(Long productId, String name, long price) {
        Product product = findActiveProductById(productId);
        product.updateDetails(name, price);

        if (productRepository.updateActiveDetails(productId, name, price, ZonedDateTime.now()) == 0) {
            throw new CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다.");
        }
        productListCacheInvalidator.invalidateAfterCommit(product.getBrandId());
        return ProductInfo.from(product);
    }

    @Transactional
    public void delete(Long productId) {
        Product product = findActiveProductById(productId);
        product.delete();
        if (productRepository.softDeleteActiveById(productId, product.getDeletedAt()) == 0) {
            throw new CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다.");
        }
        productListCacheInvalidator.invalidateAfterCommit(product.getBrandId());
    }

    private Brand findActiveBrandById(Long brandId) {
        if (brandId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "브랜드 ID는 필수입니다.");
        }

        return brandRepository.findActiveById(brandId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "브랜드를 찾을 수 없습니다."));
    }

    private Brand findActiveBrandByIdWithSharedLock(Long brandId) {
        if (brandId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "브랜드 ID는 필수입니다.");
        }

        return brandRepository.findActiveByIdWithSharedLock(brandId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "브랜드를 찾을 수 없습니다."));
    }

    private Product findActiveProductById(Long productId) {
        Product product = productRepository.findById(productId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다."));
        if (product.getDeletedAt() != null) {
            throw new CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다.");
        }
        return product;
    }
}
