package com.loopers.application.product;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.like.LikeRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Comparator;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
public class ProductFacade {

    private final BrandRepository brandRepository;
    private final LikeRepository likeRepository;
    private final ProductRepository productRepository;

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

    @Transactional(readOnly = true)
    public List<CustomerProductInfo> getCustomerList(Long brandId, int page, int size, String sort) {
        if (page < 0 || size < 1 || size > 100) {
            throw new CoreException(ErrorType.BAD_REQUEST, "페이지 입력이 올바르지 않습니다.");
        }
        Comparator<Product> comparator = customerComparator(sort);
        List<Product> products = productRepository.findAllActive().stream()
            .filter(product -> brandId == null || product.getBrandId().equals(brandId))
            .sorted(comparator)
            .toList();
        long offset = (long) page * size;
        int from = (int) Math.min(offset, products.size());
        int to = (int) Math.min(offset + size, products.size());
        List<Product> pageProducts = products.subList(from, to);
        List<Long> brandIds = pageProducts.stream().map(Product::getBrandId).distinct().toList();
        Map<Long, Brand> brandsById = brandRepository.findAllByIds(brandIds).stream()
            .collect(Collectors.toMap(Brand::getId, Function.identity()));
        return pageProducts.stream()
            .map(product -> CustomerProductInfo.from(
                product,
                brandsById.get(product.getBrandId()),
                likeRepository.countByProductId(product.getId())
            ))
            .toList();
    }

    private Comparator<Product> customerComparator(String sort) {
        if (sort == null || sort.isBlank() || "latest".equals(sort)) {
            return Comparator.comparing(Product::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Product::getId, Comparator.reverseOrder());
        }
        if ("price_asc".equals(sort)) {
            return Comparator.comparingLong(Product::getPrice)
                .thenComparing(Product::getId);
        }
        if ("likes_desc".equals(sort)) {
            return Comparator.comparingLong((Product product) -> likeRepository.countByProductId(product.getId()))
                .reversed()
                .thenComparing(Product::getId, Comparator.reverseOrder());
        }
        throw new CoreException(ErrorType.BAD_REQUEST, "정렬 입력이 올바르지 않습니다.");
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
        Brand brand = findActiveBrandById(brandId);
        Product product = Product.create(brand.getId(), name, price);

        Product savedProduct = productRepository.save(product);
        return ProductInfo.from(savedProduct);
    }

    @Transactional
    public ProductInfo changeStock(Long productId, long quantity) {
        Product product = findActiveProductById(productId);
        product.changeStockTo(quantity);

        Product savedProduct = productRepository.save(product);
        return ProductInfo.from(savedProduct);
    }

    @Transactional
    public ProductInfo update(Long productId, String name, long price) {
        Product product = findActiveProductById(productId);
        product.updateDetails(name, price);

        Product savedProduct = productRepository.save(product);
        return ProductInfo.from(savedProduct);
    }

    @Transactional
    public void delete(Long productId) {
        Product product = findActiveProductById(productId);
        product.delete();
        productRepository.save(product);
    }

    private Brand findActiveBrandById(Long brandId) {
        if (brandId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "브랜드 ID는 필수입니다.");
        }

        return brandRepository.findActiveById(brandId)
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
