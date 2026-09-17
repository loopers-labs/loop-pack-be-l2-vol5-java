package com.loopers.infrastructure.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "products")
public class ProductJpaEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, updatable = false)
    private long brandId;
    @Column(nullable = false, length = 100)
    private String name;
    @Column(nullable = false)
    private long price;
    @Column(nullable = false)
    private int stock;
    @Column(nullable = false)
    private boolean deleted;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected ProductJpaEntity() { }

    ProductJpaEntity(long brandId, String name, long price, int stock) {
        this.brandId = brandId;
        this.createdAt = Instant.now();
        update(name, price, stock, false);
    }

    void update(String name, long price, int stock, boolean deleted) {
        this.name = name;
        this.price = price;
        this.stock = stock;
        this.deleted = deleted;
    }

    Long getId() { return id; }
    long getBrandId() { return brandId; }
    String getName() { return name; }
    long getPrice() { return price; }
    int getStock() { return stock; }
    boolean isDeleted() { return deleted; }
}
