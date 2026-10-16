package com.loopers.application.product;

import com.loopers.application.user.AdminAuthorization;
import com.loopers.domain.user.UserRole;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductListRevisionRepository;
import com.loopers.domain.product.ProductQueryRepository;
import com.loopers.domain.product.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class AdminProductService {

    private final BrandRepository brandRepository;
    private final ProductRepository productRepository;
    private final ProductQueryRepository productQueryRepository;
    private final ProductListRevisionRepository revisions;

    public AdminProductService(BrandRepository brandRepository,
                               ProductRepository productRepository, ProductQueryRepository productQueryRepository,
                               ProductListRevisionRepository revisions) {
        this.brandRepository = brandRepository;
        this.productRepository = productRepository;
        this.productQueryRepository = productQueryRepository;
        this.revisions = revisions;
    }

    public AdminProductInfo create(UserRole requester, long brandId, String name, long price, int stockQuantity) {
        AdminAuthorization.requireAdmin(requester);
        Brand brand = brandRepository.lockById(brandId)
            .filter(value -> !value.isDeleted())
            .orElseThrow(() -> new ProductQueryException(ProductQueryException.Reason.BRAND_NOT_FOUND));
        Product product = productRepository.save(new Product(brand, name, price, stockQuantity));
        return infoAfterMutation(product, true);
    }

    public AdminProductInfo update(UserRole requester, long productId, String name, long price) {
        AdminAuthorization.requireAdmin(requester);
        Product product = lockProduct(productId, false);
        String previousName = product.getName();
        long previousPrice = product.getPrice();
        product.update(name, price);
        boolean changed = !previousName.equals(product.getName()) || previousPrice != product.getPrice();
        return infoAfterMutation(productRepository.save(product), changed);
    }

    public AdminProductInfo changeStock(UserRole requester, long productId, int stockQuantity) {
        AdminAuthorization.requireAdmin(requester);
        Product product = lockProduct(productId, false);
        int previousStock = product.getStockQuantity();
        product.changeStockQuantityTo(stockQuantity);
        return infoAfterMutation(productRepository.save(product), previousStock != product.getStockQuantity());
    }

    public AdminProductInfo delete(UserRole requester, long productId) {
        AdminAuthorization.requireAdmin(requester);
        Product product = lockProduct(productId, true);
        boolean changed = !product.isDeleted();
        product.delete(ZonedDateTime.now().truncatedTo(ChronoUnit.MICROS));
        return infoAfterMutation(productRepository.save(product), changed);
    }

    private AdminProductInfo infoAfterMutation(Product product, boolean changed) {
        AdminProductInfo result = info(product);
        if (changed) {
            revisions.advance();
        }
        return result;
    }

    private AdminProductInfo info(Product product) {
        long likeCount = productQueryRepository.findDetail(product.getId(), true).orElseThrow(this::productNotFound).likeCount();
        return AdminProductInfo.from(product, likeCount);
    }

    private Product lockProduct(long productId, boolean includeDeleted) {
        long brandId = productRepository.findBrandId(productId).orElseThrow(this::productNotFound);
        Brand brand = brandRepository.lockById(brandId).orElseThrow(this::productNotFound);
        Product product = productRepository.lockById(productId).orElseThrow(this::productNotFound);
        if (!includeDeleted && (brand.isDeleted() || product.isDeleted())) {
            throw productNotFound();
        }
        return product;
    }

    private ProductQueryException productNotFound() {
        return new ProductQueryException(ProductQueryException.Reason.PRODUCT_NOT_FOUND);
    }
}
