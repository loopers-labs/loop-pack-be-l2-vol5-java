package com.loopers.domain.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "product_list_revision")
public class ProductListRevision {

    @Id
    private Long id;

    @Column(name = "revision", nullable = false)
    private long revision;

    protected ProductListRevision() {
    }

    public long getRevision() {
        return revision;
    }
}
