package com.loopers.product.application;

import com.loopers.brand.domain.Brand;
import com.loopers.brand.domain.BrandRepository;
import com.loopers.like.domain.LikeRepository;
import com.loopers.product.domain.Product;
import com.loopers.product.domain.ProductNameValidator;
import com.loopers.product.domain.ProductRepository;
import com.loopers.product.domain.ProductSort;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import com.loopers.support.page.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ProductUseCase {

    private final ProductRepository productRepository;
    private final BrandRepository brandRepository;
    private final LikeRepository likeRepository;
    private final ProductNameValidator nameValidator;

    public ProductUseCase(
        ProductRepository productRepository,
        BrandRepository brandRepository,
        LikeRepository likeRepository
    ) {
        this.productRepository = productRepository;
        this.brandRepository = brandRepository;
        this.likeRepository = likeRepository;
        this.nameValidator = new ProductNameValidator(productRepository);
    }

    @Transactional
    public Product create(Long brandId, String name, long price) {
        requireActiveBrand(brandId);
        Product product = new Product(brandId, name, price);
        nameValidator.validateNotDuplicated(brandId, product.getName());
        return productRepository.save(product);
    }

    @Transactional
    public Product changeStock(Long productId, int quantity) {
        Product product = findRequired(productId);
        product.changeStock(quantity);
        return productRepository.save(product);
    }

    @Transactional(readOnly = true)
    public Product find(Long productId) {
        return findRequired(productId);
    }

    @Transactional(readOnly = true)
    public List<Product> findAll(Long brandId, int page, int size) {
        return productRepository.findAll(brandId, page, size);
    }

    @Transactional
    public Product update(Long productId, String name, long price, Long brandId) {
        Product product = findRequired(productId);
        if (product.isDeleted()) {
            throw new CoreException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        Long requestedBrandId = brandId == null ? product.getBrandId() : brandId;
        if (!product.getBrandId().equals(requestedBrandId)) {
            throw new CoreException(ErrorCode.BRAND_CHANGE_NOT_ALLOWED);
        }
        Product candidate = new Product(requestedBrandId, name, price);
        nameValidator.validateNotDuplicated(
            product.getBrandId(),
            candidate.getName(),
            productId
        );
        product.update(candidate.getName(), candidate.getPrice(), requestedBrandId);
        return productRepository.save(product);
    }

    @Transactional
    public void delete(Long productId) {
        Product product = findRequired(productId);
        product.delete();
        productRepository.save(product);
    }

    @Transactional(readOnly = true)
    public List<ProductView> findCustomerProducts(
        Long brandId,
        ProductSort sort,
        int page,
        int size
    ) {
        if (brandId != null && !isActiveBrand(brandId)) {
            return List.of();
        }
        ProductSort requestedSort = sort == null ? ProductSort.LATEST : sort;
        return productRepository.findCustomerProducts(brandId, requestedSort, page, size).stream()
            .map(product -> new ProductView(
                product,
                likeRepository.countByProductId(product.getId())
            ))
            .toList();
    }

    @Transactional(readOnly = true)
    public PageResult<CustomerProduct> findCustomerProductPage(
        Long brandId,
        ProductSort sort,
        int page,
        int size
    ) {
        List<CustomerProduct> content = findCustomerProducts(brandId, sort, page, size).stream()
            .map(view -> new CustomerProduct(
                view.product(),
                findBrand(view.product().getBrandId()),
                view.likeCount()
            ))
            .toList();
        long totalElements = brandId != null && !isActiveBrand(brandId)
            ? 0
            : productRepository.countCustomerProducts(brandId);
        return new PageResult<>(content, page, size, totalElements);
    }

    @Transactional(readOnly = true)
    public CustomerProduct findCustomerProduct(Long productId) {
        Product product = findRequired(productId);
        if (product.isDeleted()) {
            throw new CoreException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        return new CustomerProduct(
            product,
            findBrand(product.getBrandId()),
            likeRepository.countByProductId(productId)
        );
    }

    @Transactional(readOnly = true)
    public PageResult<AdminProduct> findAdminProductPage(Long brandId, int page, int size) {
        List<AdminProduct> content = productRepository.findAll(brandId, page, size).stream()
            .map(product -> new AdminProduct(product, findBrand(product.getBrandId())))
            .toList();
        return new PageResult<>(content, page, size, productRepository.countAll(brandId));
    }

    @Transactional(readOnly = true)
    public AdminProduct findAdminProduct(Long productId) {
        Product product = findRequired(productId);
        return new AdminProduct(product, findBrand(product.getBrandId()));
    }

    private Product findRequired(Long productId) {
        return productRepository.findById(productId)
            .orElseThrow(() -> new CoreException(ErrorCode.PRODUCT_NOT_FOUND));
    }

    private Brand requireActiveBrand(Long brandId) {
        Brand brand = brandRepository.findById(brandId)
            .orElseThrow(() -> new CoreException(ErrorCode.BRAND_NOT_FOUND));
        if (brand.isDeleted()) {
            throw new CoreException(ErrorCode.BRAND_NOT_FOUND);
        }
        return brand;
    }

    private boolean isActiveBrand(Long brandId) {
        return brandRepository.findById(brandId)
            .filter(brand -> !brand.isDeleted())
            .isPresent();
    }

    private Brand findBrand(Long brandId) {
        return brandRepository.findById(brandId)
            .orElseThrow(() -> new CoreException(ErrorCode.BRAND_NOT_FOUND));
    }

    public record ProductView(Product product, long likeCount) {
    }

    public record CustomerProduct(Product product, Brand brand, long likeCount) {
    }

    public record AdminProduct(Product product, Brand brand) {
    }
}
