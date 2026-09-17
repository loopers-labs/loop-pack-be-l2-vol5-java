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

    public ProductApplicationService(ProductRepository products, BrandRepository brands) {
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

    private Product locked(long id) {
        return products.findByIdForUpdate(new ProductId(id)).orElseThrow(ProductNotFoundException::new);
    }
}
