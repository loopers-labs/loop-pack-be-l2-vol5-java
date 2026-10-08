package com.loopers.infrastructure.product.fixture;

import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;

@Component
@Transactional
public class ProductFixture {
    @Autowired private ProductRepository products;
    @Autowired private ProductJpaRepository productRows;
    @PersistenceContext private EntityManager entityManager;

    public Product createProduct(long brandId, String name, long price, int stock) {
        Product product = Product.create(brandId, name, price);
        product.setStock(stock);
        return products.save(product);
    }

    public Product updateProduct(long id, String name, long price) {
        Product product = products.findById(id).orElseThrow();
        product.update(name, price);
        return products.save(product);
    }

    public void deleteProduct(long id) {
        Product product = products.findById(id).orElseThrow();
        product.delete();
        products.save(product);
    }

    @Transactional(readOnly = true)
    public Product product(long id) {
        return products.findById(id).orElseThrow();
    }

    @Transactional(readOnly = true)
    public long rowCount() {
        return productRows.count();
    }

    @Transactional(readOnly = true)
    public List<Long> activeProductIds(long brandId) {
        return products.findActiveIdsByBrandId(brandId);
    }

    @Transactional(readOnly = true)
    public long productCount(long brandId) {
        return entityManager
                .createQuery(
                        "select count(p) from ProductJpaEntity p where p.brandId = :brandId",
                        Long.class)
                .setParameter("brandId", brandId)
                .getSingleResult();
    }

    public void createdAt(long productId, ZonedDateTime time) {
        entityManager
                .createQuery("update ProductJpaEntity p set p.createdAt = :time where p.id = :id")
                .setParameter("time", time)
                .setParameter("id", productId)
                .executeUpdate();
    }

    public void createdAtForBrand(long brandId, ZonedDateTime time) {
        entityManager
                .createQuery(
                        "update ProductJpaEntity p set p.createdAt = :time where p.brandId ="
                                + " :brandId")
                .setParameter("time", time)
                .setParameter("brandId", brandId)
                .executeUpdate();
    }
}
