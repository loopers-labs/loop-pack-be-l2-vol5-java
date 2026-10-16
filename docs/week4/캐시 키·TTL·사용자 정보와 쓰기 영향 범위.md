# 캐시 키·TTL·사용자 정보와 쓰기 영향 범위

상품 기본 정보, 상품별 좋아요 수, 상품별 품절 여부를 별도 캐시로 저장하고 응답할 때 합친다. 재고는 수량 대신 품절 여부만 저장하므로 품절 전환 시 해당 상품의 품절 캐시만 바꿀 수 있다.

## 1. 어떤 키로 구분하나


| 캐시 대상      | 키 본문                                                            | revision 범위          |
| ---------- | --------------------------------------------------------------- | -------------------- |
| 상품 목록      | `v4:products?sort={sort}&brand={brand}&page={page}&size={size}` | 상품·브랜드 공통            |
| 상품 상세      | `v4:products/{id}`                                              | 상품·브랜드 공통            |
| 내 좋아요 목록   | `v4:users/{userId}/likes?page={page}&size={size}`               | 상품·브랜드 공통 + 해당 사용자   |
| 고객 브랜드 목록  | `v4:brands?page={page}&size={size}`                             | 상품·브랜드 공통            |
| 관리자 브랜드 목록 | `v4:admin:brands?page={page}&size={size}`                       | 상품·브랜드 공통            |
| 좋아요 수      | `v4:products/{productId}/like-counts`                           | 상품·브랜드 공통            |
| 품절 여부      | `v4:products/{productId}/stocks`                                | 상품·브랜드 공통 + 해당 상품 재고 |


필터·정렬·페이지·크기를 구분하며 브랜드 미지정은 `all`, 생략한 조건은 기본값으로 정규화한다. 좋아요 수·품절 여부는 상품 ID 조합이 아닌 상품별 키여서 서로 다른 목록에서도 재사용한다.

실제 Redis 키는 `캐시이름::키본문:rev=...`이다. 여러 범위에 속하면 각각의 revision을 붙인다. 데이터 변경 시 기존 키를 찾아 삭제하는 대신 DB revision을 바꿔 새 키를 사용하고, 이전 키는 TTL로 정리한다. revision은 UUID 토큰이며 숫자의 대소를 비교하지 않는다.

구현: [CacheKeys](../../apps/commerce-api/src/main/java/com/loopers/support/cache/CacheKeys.java) · [CacheVersions](../../apps/commerce-api/src/main/java/com/loopers/support/cache/CacheVersions.java)

## 2. TTL은 어떻게 적용하나


| 항목               | 정책                            |
| ---------------- | ----------------------------- |
| 기본 TTL           | 5분                            |
| 만료 분산            | 저장마다 ±30초 Jitter 적용: 270~330초 |
| hit              | TTL을 연장하지 않음                  |
| 공통 좋아요 수·좋아요순 목록 | 좋아요 등록·취소 시 무효화하지 않고 TTL로 갱신  |
| 이전 revision 키    | 새 요청에서 사용하지 않고 TTL로 정리        |


TTL은 삭제 상품 비노출이나 늦은 채움 방지를 대신하지 않는다. 이 보호는 DB revision으로 처리한다. 고정 TTL을 별도로 지정하는 실험 프로필은 기본 정책과 구분한다.

구현: [ReadCacheTtl](../../apps/commerce-api/src/main/java/com/loopers/support/cache/ReadCacheTtl.java)

## 3. 사용자별 정보는 어떻게 분리하나

- 공통 상품 캐시는 상품 정보·전체 좋아요 수·품절 여부를 담고, 특정 사용자의 좋아요 목록·주문·포인트는 공용 키에 넣지 않는다.
- 내 좋아요 목록은 **사용자 ID가 포함된 키**와 **사용자별 revision**으로 분리한다. 등록·취소 시 다른 사용자의 목록은 무효화하지 않는다.
- cache hit도 인증·소유권 검사를 생략하지 않는다. 다른 사용자의 내 좋아요 접근은 404, 고객의 관리자 브랜드 접근은 403으로 거절한다.
- 고객·관리자 브랜드 목록은 키를 분리해 삭제 포함 여부와 접근 권한이 다른 응답을 공유하지 않는다.

## 4. 어떤 쓰기가 어느 캐시에 영향을 주나

**상품·브랜드 공통 범위**는 상품 목록·상세, 고객·관리자 브랜드 목록, 모든 사용자의 내 좋아요 목록, 상품별 좋아요 수·품절 캐시를 뜻한다. 코드의 이름은 `catalog`다. 개별 영향 상품을 추적하는 대신 이 범위를 함께 무효화한다.


| 기존 쓰기 경로              | 갱신하는 revision       | 캐시 반영                                              |
| --------------------- | ------------------- | -------------------------------------------------- |
| 관리자 상품 생성·이름/가격 수정·삭제 | `catalog`           | 상품·브랜드 공통 범위 전체                                    |
| 관리자 브랜드 생성·수정·삭제      | `catalog`           | 공통 범위 전체. 브랜드 삭제 시 소속 상품 일괄 삭제도 포함                 |
| 좋아요 등록·취소             | `likes:{userId}`    | 해당 사용자의 내 좋아요 목록만 즉시 무효화. 공통 좋아요 수·순위는 TTL 유지      |
| 관리자 재고 변경             | `stock:{productId}` | 품절 여부가 바뀐 상품의 품절 캐시만 무효화                           |
| 주문 확정에 따른 재고 차감       | `stock:{productId}` | 동일하게 품절 여부가 바뀐 상품만 무효화                             |
| 상품 생성에 따른 최초 재고 저장    | `stock:{productId}` | 새 상품의 재고 revision 갱신. 상품 생성의 공통 revision 갱신도 함께 적용 |


재고는 `100 → 99`이면 유지하고 `1 → 0`, `0 → 10`이면 갱신한다. 기존 잠금 조회에서 변경 전 상태를 확보하며, 이전 상태를 알 수 없는 저장은 보수적으로 갱신한다.

상품·브랜드·재고·좋아요 저장소의 `save/delete` 경계에 연결해 기존 관리자·좋아요·주문 경로를 함께 처리한다. **데이터와 revision은 같은 DB 트랜잭션에서 갱신**하므로 롤백되면 둘 다 되돌아간다. 재고를 차감하지 않는 주문 생성이나 포인트 충전은 이 캐시의 무효화 대상이 아니다. 새 쓰기 경로나 직접 SQL 변경을 추가할 때는 revision 갱신도 연결해야 한다.

구현: [CacheWriteConfiguration](../../apps/commerce-api/src/main/java/com/loopers/config/cache/CacheWriteConfiguration.java) · [StockCacheState](../../apps/commerce-api/src/main/java/com/loopers/config/cache/StockCacheState.java)
