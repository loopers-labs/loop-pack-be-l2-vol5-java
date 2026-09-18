package com.loopers.product.infrastructure;

import com.loopers.product.domain.Product;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

interface ProductJpaRepository extends JpaRepository<Product, Long> {

    List<Product> findAllByBrandId(Long brandId);

    List<Product> findAllByBrandIdAndName(Long brandId, String name);

    List<Product> findAllByBrandId(Long brandId, Pageable pageable);

    long countByBrandId(Long brandId);

    @Query("""
        select count(p) from Product p
        where p.deletedAt is null
          and (:brandId is null or p.brandId = :brandId)
        """)
    long countCustomerProducts(@Param("brandId") Long brandId);

    @Query("""
        select p from Product p
        where p.deletedAt is null
          and (:brandId is null or p.brandId = :brandId)
        order by p.createdAt desc, p.id asc
        """)
    List<Product> findCustomerProductsByLatest(
        @Param("brandId") Long brandId,
        Pageable pageable
    );

    @Query("""
        select p from Product p
        where p.deletedAt is null
          and (:brandId is null or p.brandId = :brandId)
        order by p.price asc, p.id asc
        """)
    List<Product> findCustomerProductsByPrice(
        @Param("brandId") Long brandId,
        Pageable pageable
    );

    @Query("""
        select p from Product p left join Like l on l.productId = p.id
        where p.deletedAt is null
          and (:brandId is null or p.brandId = :brandId)
        group by p.id
        order by count(l) desc, p.id asc
        """)
    List<Product> findCustomerProductsByLikes(
        @Param("brandId") Long brandId,
        Pageable pageable
    );
}
