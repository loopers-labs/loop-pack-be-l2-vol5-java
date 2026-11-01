# 상품 상세 캐시 구현 검증

4주차 구현 과제에서 고객 상품 상세에 붙인 Redis cache-aside의 검증 기록이다. 설계는 [상품 조회 설계 4.4](../../system-design/features/product-cache.md#44-상품-조회)를 따른다.

## 구현 요약

- 상품명·브랜드 이름·가격·재고만 상품별 JSON 하나로 캐시한다(`commerce:product-detail:v1:{productId}`). 좋아요 수와 상품·브랜드 노출 여부는 매 조회 DB에서 확인해 합성한다.
- 고정 TTL(평시 5분, 테스트 10초)을 저장할 때 정하고 hit에서 연장하지 않는다.
- 상품 수정·삭제·재고 변경, 브랜드 이름 변경·삭제, 주문 확정의 재고 변경은 commit 뒤 해당 key를 삭제한다(`AfterCommitRunner`). rollback이면 삭제하지 않는다.
- Redis 오류와 캐시 값 형식 오류는 로그를 남기고 DB로 응답한다. connect·command timeout은 각 200ms다.
- 목록은 캐시하지 않는다.

## 문서

|문서|내용|
|---|---|
|[01 — DB-only·miss·hit 비교](01-cache-performance.md)|세 경로의 응답·SQL 수·읽은 행·지연, 형식 오류 처리, cold 동시 miss 관측, Quest hit 조건 판정|
|[02 — 변경·rollback·늦은 채움·후처리 실패](02-consistency.md)|영향 쓰기별 무효화, rollback, commit 뒤 DEL 실패, 늦은 캐시 채움의 결정적 재현과 복구 판정|
|[03 — Redis 중단·재시작](03-redis-failure.md)|실제 Redis 중단 중 DB 우회와 대기 시간, 재시작 뒤 재채움|

## Quest 검증 장면별 결과

|장면|결과|문서|
|---|---|---|
|동일 입력의 DB-only·miss·hit|결과 일치. hit의 비싼 원본 호출 감소는 **미충족**(hit 확인 SQL이 같은 PK 조회·브랜드 조인·좋아요 COUNT를 수행). 정책은 유지|[01](01-cache-performance.md)|
|관리자 가격 수정 commit 후 재조회|새 가격·가격순 목록 반영, 과거 주문 금액 유지|[02](02-consistency.md)|
|상품·브랜드 일괄 삭제|삭제 상품 비노출, 다른 브랜드·과거 주문 유지|[02](02-consistency.md)|
|나머지 영향 쓰기|브랜드 이름·재고·주문 확정 반영. 좋아요는 캐시에 담지 않고 DB 수를 반영|[02](02-consistency.md)|
|DB rollback|변경 전 결과 유지, 무효화 미실행|[02](02-consistency.md)|
|commit 뒤 무효화 실패|DB commit 유지, 옛 기본정보는 TTL 만료까지 허용 뒤 DB 값으로 재채움|[02](02-consistency.md)|
|이전 조회의 늦은 캐시 채움|latch로 순서를 통제해 재현, 만료 뒤 복구 확인|[02](02-consistency.md)|
|Redis 중단·재시작|중단 중 약 0.42초 대기 뒤 DB 값 응답, 재시작 뒤 재채움(실제 앱 수동 1회)|[03](03-redis-failure.md)|

자동 테스트는 Testcontainers MySQL·Redis를 쓰는 `ProductCache*IntegrationTest`, `AfterCommitRunnerIntegrationTest`이며, 최종 `./gradlew :apps:commerce-api:cleanTest :apps:commerce-api:check`는 377건 fail/error/skip 0, Checkstyle·ArchUnit 통과다.

## 한계

- 실제 앱 측정은 로컬 단일 머신의 순차 요청과 소량 진단 자료다. 동시 부하·처리량·CPU는 측정하지 않았다.
- Redis 중단·재시작은 자동 테스트가 아니라 실제 앱에서 수동으로 1회 확인했다.
- 관리자 쓰기 중 실제 Redis 장애로 인한 DEL 실패는 HTTP 관리자 경계 때문에 재현하지 않고, 실패 주입 테스트로 확인했다.
