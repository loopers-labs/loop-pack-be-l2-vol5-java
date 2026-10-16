package com.loopers.support.cache;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "read_cache_revision")
public class ReadRevision {
    @Id @Column(length = 100) private String namespace;
    @Column(nullable = false, length = 36) private String revision;
    protected ReadRevision() { }
}
