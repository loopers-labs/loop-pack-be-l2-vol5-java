package com.loopers.domain.product;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * AG-03 포함 개념 목록 revision (TB-09 catalog_revision, DR-37). 캐시하는 고객 상품 목록마다 한 행이다.
 * 지금은 latest 첫 페이지 하나({@link #PRODUCT_LIST_LATEST})뿐이며, 행은 배포 SQL 로 revision 0 인 채 만든다.
 * 만든 뒤에는 엔티티로 읽어 고치지 않고 조건 갱신으로만 올린다. 변경 감지로 저장하면 그 사이 올라간 값을 읽은 값으로 덮는다.
 */
@Entity
@Table(name = "catalog_revision",
    uniqueConstraints = @UniqueConstraint(name = "ux_catalog_revision_name", columnNames = "name"))
public class CatalogRevisionModel extends BaseEntity {
    public static final String PRODUCT_LIST_LATEST = "product_list_latest";

    @Column(name = "name", nullable = false, updatable = false, length = 50)
    private String name;

    @Column(name = "revision", nullable = false)
    private long revision;

    protected CatalogRevisionModel() {}

    public CatalogRevisionModel(String name) {
        this.name = name;
        this.revision = 0L;
    }

    public String getName() {
        return name;
    }

    public long getRevision() {
        return revision;
    }
}
