# ADR 0021: 가격순 첫 페이지에 cache-aside와 commit 후 삭제를 사용한다

Status: Accepted
Date: 2026-10-15

## 범위와 선택 이유

브랜드 필터·가격순·page=0·size=20의 상품 페이지에 기본 Redis cache-aside를 사용한다. 캐시는 처음 배우는 단계이므로 GET/SET/DEL을 기준으로 구현한다. 과제의 Redis 학습 구현이며 운영 도입의 필요성은 실제 API 측정·요청량·요구 성능으로 별도 판단한다.

측정 결과 이 경로의 원본은 가격순 인덱스로 상품 20행에서 멈춰 데이터가 커져도 거의 늘지 않았다. 같은 조건에서 hit가 아낀 요청당 DB 시간은 브랜드 990개일 때 2~3%, 87,390개일 때 약 0.2ms(15~16%)였고 API는 hit가 오히려 약 1ms 느렸다. 그래서 운영 적용 시에는 캐시 없는 DB 조회를 우선 검토한다([캐시 대상 기준](../week4/design.md#cache-target-criterion), [상세](../week4/cache-db-work.md)).

목록 응답은 items·page·size이고 총상품수는 세거나 반환하지 않는다([ADR 0022](0022-product-list-without-total-count.md)). 캐시 값은 상품 ID·이름·가격·생성 시각이며 좋아요·브랜드 표시·재고·개인 정보는 저장하지 않는다.

## 조회와 변경 흐름

1. DB에서 브랜드 유효성을 확인한다.
2. Redis GET으로 페이지를 읽는다. hit도 캐시 상품의 활성 ID를 DB에서 확인한다. 삭제 상품이 있으면 페이지를 다시 조회한다.
3. miss·손상 값·Redis 실패면 기존 DB 필터·가격순·LIMIT/OFFSET으로 페이지를 고르고 SET+TTL 300초로 저장한다.
4. 상품 ID의 현재 좋아요 카운터를 한 번에 조회해 브랜드 정보와 합친다. 목록의 개인별 `isLiked`는 [ADR 0023](0023-product-list-personal-like-state.md)에 따라 공통 페이지 선택 후 DB에서 별도로 합성한다. 공통 캐시에는 포함하지 않는다.
5. ProductFacade 생성·이름/가격 변경·삭제와 BrandFacade 삭제가 같은 트랜잭션에서 PricePageChanged를 발행한다. AFTER_COMMIT listener가 해당 브랜드의 첫 페이지 키를 DEL한다. rollback에서는 실행하지 않는다.
6. 좋아요·재고·브랜드 이름/설명은 저장 값과 가격순 선택을 바꾸지 않아 DEL하지 않는다.

키는 `prefix:brand:{brandId}:sort:price_asc:page:0:size:20`이다. local 기본 prefix는 `commerce:price-page:v3:local`, benchmark는 `commerce:price-page:v3:benchmark`다. 같은 프로필에서 다른 DB로 연결하면 전용 prefix를 명시한다. 범위가 첫 페이지 하나여서 wildcard 삭제나 SCAN은 구현하지 않는다.

## 정합성과 오류 정책

정상 변경 API 완료 후 시작한 순차 조회는 새 결과를 반환한다. DEL 실패·동시 조회의 늦은 채움에서는 표시 이름/가격/순서가 일시적으로 오래될 수 있다. TTL은 각 SET부터 300초이며 hit로 연장하지 않는다. DB 변경 후 모든 요청이 반드시 300초 안에 최신이라는 전역 상한은 주장하지 않는다.

삭제 상품/브랜드 비노출·소유권·주문 금액/재고/포인트 판단은 완화하지 않는다. 주문 판단은 캐시 표시 가격을 쓰지 않는다(삭제 실패로 옛 가격이 캐시에 남아도 새 주문 단가는 DB 가격임을 `staleCachedPriceAfterFailedEvictionIsNeverUsedForNewOrders`로 확인했다). 브랜드 변경 API는 새로 만들지 않는다. 아직 commit되지 않은 쓰기 트랜잭션 안의 조회는 캐시 읽기/저장을 우회한다.

Redis master 전용 endpoint와 200ms 연결/명령 제한을 사용한다. Redis 오류는 DB 우회 또는 DEL 실패 로그로 처리하고 이미 commit된 변경을 rollback됐다고 보고하지 않는다. DB 오류는 기존 예외로 전파한다. 재연결만으로 옛 값이 즉시 최신이 된다고 보장하지 않는다. 만료·성공한 관련 DEL·명시적 자신의 키 삭제 뒤 miss에서 다시 채운다.

## 비용과 로컬 비교

비어 있지 않은 브랜드 필터 가격순 요청에서 익명 조회는 캐시 없음/miss에 브랜드·상품 페이지·좋아요 카운터, hit에 브랜드·활성 ID·좋아요 카운터를 조회한다. 사용자 헤더가 있으면 사용자 확인과 개인 관계 조회가 추가된다. SQL 수가 같아도 각 쿼리가 하는 일과 읽는 행이 다르므로 실제 계획과 API 시간을 함께 본다. Redis 왕복·JSON 처리·상품 변경 시 DEL 비용이 남는다. 같은 키의 동시 miss는 중복 DB 조회를 허용한다.

비교용 local provider는 Caffeine에 같은 불변 DTO를 저장한다. TTL 300초·commit 후 삭제·활성 ID 확인을 공유한다. 최대 1,000개 엔트리는 실험 상한이다. 다른 앱으로 무효화를 전파하지 않아 다중 앱에서 정상 변경 직후 최신성 정책을 만족한다고 주장하지 않는다. 기본 provider는 Redis다.

`enabled=false`는 조회/저장과 after-commit DEL을 모두 끈다. 같은 DB를 공유하는 캐시 사용 앱과 비사용 쓰기 앱의 혼합 배포를 지원하는 설정으로 쓰지 않는다. 직접 SQL 변경은 앱의 무효화를 거치지 않는다.

## 검증 근거

정상 갱신·상품 추가/삭제·브랜드 삭제·좋아요 합성·rollback·쓰기 트랜잭션 우회·늦은 채움·실제 Redis 중단/재시작·실패와 회복을 통합 테스트로 확인한다. 재시작 테스트는 자신의 키를 명시적으로 지운 뒤 재채움을 검증하며 단순 재연결만으로 최신을 보장하지 않는다. 만료 테스트는 자신의 키 TTL을 100ms로 줄여 확인한다.

[다섯 경우의 API 비교](../week4/price-cache-comparison.md), [상품 쏠림·동시 조회](../week4/price-cache-skew.md), [최신 실행 기록과 한계](../week4/implementation-log.md#price-cache-without-count)를 근거로 한다. Redis의 보편적 성능 우위나 운영 hit rate는 이 실험으로 확정하지 않는다.
