package com.loopers.domain.product;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductRepository {
    Product save(Product product);

    /** 삭제되지 않은 상품만 조회한다. */
    Optional<Product> findActive(Long id);

    /**
     * 삭제되지 않은 상품 하나를 배타 잠금(FOR UPDATE)으로 조회한다. 이 상품을 바꾸는 요청(확정·관리자 수정)은 먼저 잡은
     * 트랜잭션이 끝날 때까지 기다린다. 반드시 Facade 의 트랜잭션 안에서, 이 상품을 처음 읽는 조회로 호출한다.
     */
    Optional<Product> findActiveForUpdate(Long id);

    /** id 목록에 해당하는 삭제되지 않은 상품을 한 번에 조회한다. 삭제됐거나 없는 id 는 결과에 없다. */
    List<Product> findAllActiveByIds(Collection<Long> ids);

    /**
     * id 목록에 해당하는 삭제되지 않은 상품을 id 오름차순으로 한 번에 배타 잠금(FOR UPDATE)하며 조회한다. 삭제됐거나 없는
     * id 는 결과에 없다. 반드시 Facade 의 트랜잭션 안에서, 이 상품들을 처음 읽는 조회로 호출한다.
     */
    List<Product> findAllActiveByIdsForUpdate(Collection<Long> ids);

    /**
     * id 목록에 해당하는 상품을 <b>삭제된 상품까지 포함해</b> id 오름차순으로 한 번에 배타 잠금(FOR UPDATE)하며 조회한다.
     * 결제가 확정 실패했을 때 이미 확보했던 재고를 돌려주는 데 쓴다. 상품이 그사이 삭제됐어도 수량은 돌려받아야 하기 때문이다.
     * 없는 id 는 결과에 없다. 반드시 트랜잭션 안에서, 이 상품들을 처음 읽는 조회로 호출한다.
     */
    List<Product> findAllByIdsForUpdate(Collection<Long> ids);

    /** 삭제되지 않은 상품을 id 내림차순으로 조회한다. 정렬은 Pageable 이 아니라 구현이 고정한다. */
    Page<Product> findAllActive(Pageable pageable);

    /**
     * 고객 상품 목록. 삭제되지 않은 상품을 정렬 기준에 따라 조회하고, 브랜드를 지정하면 그 브랜드의 상품만 조회한다.
     * 존재하지 않는 브랜드이면 빈 결과다. 정렬은 Pageable 이 아니라 {@code sort} 가 정하며 동률은 id 내림차순이다.
     */
    Page<Product> findAllActive(Long brandId, ProductSort sort, Pageable pageable);

    /** 사용자가 좋아요한 삭제되지 않은 상품을, 최근에 좋아요한 순으로 조회한다. 정렬은 Pageable 이 아니라 구현이 고정한다. */
    Page<Product> findAllActiveLikedBy(Long userId, Pageable pageable);

    /**
     * 브랜드의 삭제되지 않은 상품(재고 0 포함)을 id 오름차순으로 배타 잠금(FOR UPDATE)하며 조회한다.
     * 반드시 Facade 의 트랜잭션 안에서, 이 상품들을 처음 읽는 조회로 호출한다.
     */
    List<Product> findAllActiveByBrandIdForUpdate(Long brandId);
}
