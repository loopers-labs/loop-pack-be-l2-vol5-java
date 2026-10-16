# Redis 캐시 전략 ADR

2026-10-16 · 현재 브랜치 기준

## 선택한 방식과 이유

RDB를 원본으로 두고 **Cache Aside + DB revision + TTL/Jitter + 로컬 Single Flight**를 사용한다.

과제 요구사항을 충족하기 위해 DB revision을 선택했다. 과제가 revision 자체를 강제하는 것은 아니다. [과제](../../.handoff/week4-과제.md)의 **늦은 채움·무효화 실패·Redis 복구 중에도 삭제 상품을 노출하지 않는 조건**을 커밋 후 키 삭제와 TTL만으로 충족하지 못했기 때문이다. Lua는 사용하지 않는다.

## 캐시 분리와 키 구성

상품 기본 정보와 좋아요 수·품절 여부를 따로 캐시하고 서버에서 합친다. 통합 응답 자체는 캐시하지 않아, 재고 변경 시 기본 정보까지 다시 채우지 않는다. API는 `id`, `name`, `price`, `brand`, `likeCount`, `soldOut`을 한 응답으로 반환한다.

목록은 조회 조건별로 저장하고, 좋아요 수·품절 여부는 **상품 ID별**로 저장해 여러 목록에서 재사용한다. 여러 상품의 표시 값은 Redis MGET 한 번으로 읽고, 종류별로 누락된 상품 ID만 모아 DB에서 일괄 조회한다.

| 캐시 | 키 본문 | 참조하는 revision |
| --- | --- | --- |
| 상품 목록 | `v4:products?sort={sort}&brand={brand}&page={page}&size={size}` | `catalog` |
| 상품 상세 | `v4:products/{id}` | `catalog` |
| 내 좋아요 목록 | `v4:users/{userId}/likes?page={page}&size={size}` | `catalog` + `likes:{userId}` |
| 고객 브랜드 목록 | `v4:brands?page={page}&size={size}` | `catalog` |
| 관리자 브랜드 목록 | `v4:admin:brands?page={page}&size={size}` | `catalog` |
| 좋아요 수 | `v4:products/{productId}/like-counts` | `catalog` |
| 품절 여부 | `v4:products/{productId}/stocks` | `catalog` + `stock:{productId}` |

키 생성은 `CacheKeys`에서 관리한다. 브랜드 미지정은 `all`로 표현하고 생략한 정렬·페이지 조건은 기본값으로 정규화한다. 내 좋아요는 사용자 ID로 분리하고 고객·관리자 브랜드 목록도 별도 키를 사용한다. 인증·소유권 확인은 캐시와 별개로 유지한다.

## 키 변경과 무효화 범위

실제 Redis 키는 `캐시이름::키본문:rev=...` 형태이며, 여러 그룹을 참조하면 각 revision을 순서대로 붙인다. revision은 UUID 변경 토큰으로 숫자 증가 순서를 비교하지 않는다.

**기존 키를 찾아 수정·삭제하지 않고, DB revision을 바꿔 이후 요청이 새 키를 사용하게 한다.** 이전 키는 TTL로 정리한다. 데이터와 revision은 같은 DB 트랜잭션에서 갱신하므로 함께 커밋·롤백된다.

| 변경 | 무효화 범위 |
| --- | --- |
| 상품·브랜드 생성·수정·삭제, 브랜드 소속 상품 일괄 삭제 | `catalog` 갱신. 상품 목록·상세, 브랜드 목록, 내 좋아요, 상품별 좋아요 수·품절 캐시 전체 |
| 좋아요 등록·취소 | 해당 사용자의 `likes:{userId}` 갱신. 내 좋아요 목록만 즉시 반영하고 공통 좋아요 수·순위는 TTL로 갱신 |
| 관리자 재고 변경·주문 확정 | 품절 여부가 바뀔 때 해당 상품의 `stock:{productId}`만 갱신. 다른 상품과 기본 정보 캐시는 유지 |
| 롤백 | 데이터와 revision 모두 롤백. 기존 캐시 버전 유지 |

재고 수량이 `100 → 99`이면 유지하고, `1 → 0` 또는 `0 → 10`이면 갱신한다. 기존 잠금 조회에서 얻은 변경 전 상태와 저장 후 상태를 비교해 추가 DB 조회를 피한다. 초기 생성이나 이전 상태를 확보하지 못한 저장은 보수적으로 갱신한다. 실제 주문의 수량·금액·포인트 판단은 DB 규칙을 사용한다.

상품·브랜드 변경은 해당 상품만 추적하지 않고 넓은 `catalog` 범위로 묶었다. 개별 키 추적은 줄지만, 변경 직후 관련 캐시의 miss가 늘어나는 비용을 감수한다. 새 쓰기 경로와 직접 SQL 변경도 revision 갱신을 누락하면 안 된다.

## 읽기와 늦은 채움 보호

- hit에서도 현재 DB revision을 확인하고 해당 키를 읽는다. revision은 요청 간 캐싱하지 않는다.
- 통합 상품 조회는 기본 정보 revision 1회와 상품별 표시 revision 일괄 1회로 확인한다. hit에서는 비싼 상품·집계 원본 조회를 생략한다.
- miss는 REPEATABLE_READ 트랜잭션에서 revision과 원본을 함께 읽어 저장한다. 같은 키의 동시 miss는 서버 내 Single Flight로 묶는다.
- 변경 전 조회가 늦게 끝나도 이전 revision 키에만 저장한다. 변경 완료 후 새 요청은 새 키를 사용한다.
- 통합 hit의 두 revision 조회 사이에 catalog 변경이 확인되면 새 DB 스냅샷에서 응답을 다시 구성한다.

## TTL과 장애 대응

기본 TTL은 **5분 ±30초**다. 저장마다 Jitter를 새로 적용하고 hit은 TTL을 연장하지 않는다. Jitter는 여러 키의 동시 만료를 분산하고, Single Flight는 같은 서버 안에서 같은 키의 중복 채움을 줄인다.

Redis 오류 시 제한된 대기 뒤 DB로 우회하고, 복구 후 현재 revision으로 다시 채운다. DB revision 조회 실패를 이전 캐시로 감추지는 않는다. 삭제 비노출·사용자 소유권·주문 판단은 좋아요 표시 지연 정책의 대상이 아니다. TTL만으로 모든 장애·동시 요청의 전역 최신성 상한을 보장하지 않는다.

## 비용과 적용 조건

- hit에도 DB revision 조회가 남고, 공통 revision 행에서 쓰기 잠금 경합이 생길 수 있다.
- 이전 버전 키가 TTL까지 남아 메모리를 사용한다. 상품별 키·revision 저장 비용도 발생한다.
- Single Flight는 서버 내부 범위이며, 여러 인스턴스의 합산 DB 부하를 제한하지 않는다. Bulkhead 한도·초과 요청 정책과 Redis 메모리 한도·제거 정책의 운영 설정은 후속 검토 대상이다.
- 변경과 겹쳐 이미 진행 중인 조회는 그 조회 시점의 결과를 반환할 수 있다. 현재 설계의 혼합 읽기·쓰기 부하 성능은 별도 측정이 필요하다.
- `catalog-cache` 프로필로 활성화하며 기존 고정 데이터 실험 캐시와 동시 사용하지 않는다. Write Invalidate 비교 경로는 `experiment` 프로필과 `experiment.catalog-write-invalidate.enabled=true`를 명시할 때만 사용한다.
- 배포 전 쓰기를 멈추고 [revision 테이블 SQL](migrations/05-add-read-cache-revision.sql)을 적용해 catalog 토큰을 갱신한다. 이전 인스턴스와 혼합 쓰기를 하지 않는다. 이전 고정 v4 키는 재사용하지 않는다. 이번 revision 복원은 로컬 검증까지이며 배포·운영 DB 변경은 하지 않았다.

## 검증 결과

2026-10-16 로컬 MySQL·Redis Testcontainers에서 다음 명령이 성공했다.

```bash
./gradlew :apps:commerce-api:check :modules:redis:check --continue
```

총 514개 중 **513개 성공**, 실패·오류 0개, opt-in 장시간 관찰 실험 1개 skip. 변경·늦은 채움 보호 26개, Redis 장애 복구 9개, 캐시 조회 37개와 Checkstyle·ArchUnit을 통과했다. 통합 hit 도중 삭제가 커밋되는 경우의 재조회도 확인했다.

[변경·장애 보호 검증](캐시%20변경·장애%20보호%20검증%20결과.md) · [Redis 장애 복구 검증](Redis%20장애%20복구%20검증%20결과.md)
