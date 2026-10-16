# 02 — 상세 변경·rollback·늦은 채움·캐시 후처리 실패

상태: **2026-10-15 1단계 필수 장면 실행 완료(자동 테스트 + 실제 앱 일부)**. Claude 실행 / Codex 원본 검토. 정책 선택과 실제 검증 완료를 구분한다.

## 적용할 계약

[상품 조회 설계](../../system-design/sections/04-use-cases.md#44-상품-조회)를 따른다. 상품명·Brand 이름·가격·재고만 JSON String cache-aside, 좋아요 수는 DB 조회다. 기본정보는 공통 afterCommit callback의 commit 뒤 동기 DEL이고 TTL은 시험 10초/평시 5분 고정값·hit 비연장이다. 별도 count key/증감·캐시 이벤트/AOP·hit 연장은 없다.

cache 오류는 DB 성공 유지·로그만/재시도 없음, JSON 형식 오류는 miss·DB 정상 재채움·로그다. 삭제 비노출·소유권·주문 금액/재고/포인트·과거 주문 보호는 유지한다. 일반 업무 회귀는 cache on이며 필요한 캐시 경로 비교만 off다.

## 구현한 무효화 연결

|쓰기 경로|영향 key 결정|등록 위치|
|---|---|---|
|관리자 상품 수정·삭제·최종 재고 변경|해당 상품 1개|`ProductFacade.update/delete/changeStock` TX 안|
|관리자 브랜드 이름 변경|같은 TX에서 조회한 그 브랜드의 미삭제 상품 ID 전체(`findActiveIdsByBrandId`)|`BrandFacade.update`|
|브랜드 일괄 삭제|bulk UPDATE **전에** 같은 TX에서 조회한 미삭제 상품 ID 전체|`BrandFacade.delete`|
|주문 확정|잠근 뒤 재고를 줄인 상품 ID 전체|`OrderConfirmFacade.confirm` 마지막|
|좋아요 등록·취소|무효화·채움 없음|변경 없음|
|관리자 상품 생성|없음(새 ID의 key는 존재하지 않음). 응답은 DB 조회이며 캐시에 채우지 않음|변경 없음|

공통 도구 `support.transaction.AfterCommitRunner.runAfterCommit(operation, task)`가 실제 트랜잭션 안에서만 `TransactionSynchronization.afterCommit`을 등록한다. commit 뒤 같은 요청 스레드에서 동기 실행되고, rollback이면 실행하지 않는다. 작업 예외는 잡아서 `WARN` 로그(`commit 뒤 작업이 실패했습니다. DB commit 결과는 유지하고 재시도하지 않습니다. [operation = …]`)만 남긴다. 트랜잭션 밖에서 등록하면 `IllegalStateException`으로 거절한다. 실행 중에는 아직 DB 연결이 반환되기 전이므로, DEL 실패 대기(최대 command timeout)만큼 연결 점유가 늘 수 있다. 브랜드 단위 DEL은 500개씩 나눠 보낸다.

## 검사 목록 — 실행 결과

자동 테스트는 실제 Spring 컨텍스트 + Testcontainers MySQL 8.0·Redis(`redis:latest`)를 사용한다. 캐시 포트·조회 포트는 실제 구현을 호출하는 Mockito spy로 두고, 순서 통제와 실패 주입에만 썼다. 명령은 `./gradlew :apps:commerce-api:test --tests '*ProductCache*Test' --tests '*AfterCommitRunner*Test'`(24 tests, fail/error/skip 0)이며, 최종 `cleanTest check`에도 포함됐다.

|장면|테스트(클래스.메서드)|통제 조건·기대|실제 결과|
|---|---|---|---|
|가격 commit|`ProductCacheInvalidationIntegrationTest$PriceChange.returnsNewPriceAndKeepsPastOrder`|1,000원 상품 hit 상태에서 관리자 가격 3,000원 수정|commit 직후 key 없음 → 상세 3,000·재고 8, 브랜드 가격순 `[모자 2,000, 운동화 3,000]`, 과거 주문 총액 2,000·단가 1,000·수량 2 유지. 통과|
|관리자 응답의 미commit 결과 비게시|`…$PriceChange.adminWritesDoNotPublishToCache`|관리자 생성·수정·관리자 상세|응답은 DB 값, key 없음, `put` 호출 0회. 통과|
|Product/Brand 삭제(정상 DEL)|`…$Removal.removesKeysOfDeletedProducts`|상품 3개 hit → 상품 삭제·브랜드 일괄 삭제|세 key 모두 삭제, 고객 상세 404. 통과|
|Product/Brand 삭제(key 잔존)|`…$Removal.hidesDeletedEvenOnHit`|DEL만 건너뛰어 key를 남김(무효화 실패·늦은 채움 상황)|key가 남아 hit가 돼도 삭제 상품·삭제 브랜드 상품은 404, 목록은 다른 브랜드만, 다른 브랜드 상세 정상, 과거 주문 금액 유지. 통과|
|브랜드 이름 변경|`…$OtherWrites.brandRename`|두 브랜드 상품 3개 hit|이름 바꾼 브랜드의 두 key만 삭제, 새 이름 반환, 다른 브랜드 key·이름 유지. 통과|
|관리자 재고|`…$OtherWrites.adminStockChange`|hit 상태에서 최종 재고 50|key 삭제, 재고 50 반환. 통과|
|주문 확정 재고|`…$OtherWrites.orderConfirmStockChange`|3개 hit, 그중 2개 상품 주문 확정|주문 상품 2개 key만 삭제, 재고 7·4 반환, 주문 외 상품 key 유지. 통과|
|좋아요 등록/취소|`…$OtherWrites.likesUseDatabaseCountOnly`|기본정보 hit 상태에서 좋아요 2건·중복·취소·없는 취소|좋아요 수 2 → 1을 DB에서 반영, 중복 `LIKE_ALREADY_EXISTS`, 없는 취소 `LIKE_NOT_FOUND`, key 유지·PTTL 감소만, 캐시 `evictAll`·`put` 호출 0회. 통과|
|DB rollback|`…$Rollback.rollbackSkipsEviction`|가격 변경·저장·무효화 등록 → 응답 조회(UPDATE flush 포함)를 실제 실행한 뒤 실패 주입|예외 전파, DB 가격 1,000 유지, 기존 key 값 그대로, 고객 상세 1,000, `evictAll` 호출 0회. 통과|
|callback 경계|`AfterCommitRunnerIntegrationTest` 4건|TransactionTemplate 안에서 등록|commit 뒤 같은 스레드에서 실행되고 그 시점에 다른 연결에서도 commit 결과(1건)가 보임. rollback이면 미실행·0건. 작업 예외는 전파되지 않고 로그를 남기며 다음 작업은 실행. TX 밖 등록은 `IllegalStateException`. 통과|
|commit 뒤 DEL 실패|`ProductCacheFailureIntegrationTest$EvictionFailure.keepsCommitAndRecoversAfterExpiry`|key(1,000원, PTTL 9,999ms) hit 상태에서 `evictAll`에 `RedisConnectionFailureException` 주입 → 가격 2,000 수정|관리자 응답 2,000·DB 2,000(commit 유지), WARN 로그와 주입 메시지 기록, 직후 hit는 옛 1,000. key는 수정 후 10,064ms 기다려 실제 만료, 다음 조회는 완성 상세 SQL 1회로 2,000 반환·2,000으로 재채움. 재시도 없음(`evictAll` 1회). 통과|
|늦은 fill|`ProductCacheFailureIntegrationTest$LateFill.staleFillExpiresAndRefills`|latch로 **옛 DB 읽기(1,000) → 가격 2,000 수정 commit·DEL → 옛 SET → 새 조회** 순서 고정|commit·DEL 직후 key 없음 → 옛 읽기 응답 1,000(그 요청이 읽은 DB 시점의 값), 옛 JSON 1,000이 **새 TTL**로 저장, 이후 hit 3회 모두 1,000·PTTL 9,967→8,956→7,941. 옛 값은 늦은 SET 뒤 10,071ms에 실제 만료, 다음 조회는 완성 상세 1회로 2,000·재채움 2,000. worker는 `shutdownNow`+5초 종료 확인. 통과|
|고정 TTL|`ProductCacheReadIntegrationTest$FixedTtl.hitDoesNotExtendExpiry`|채운 뒤 1초 간격 hit 4회|PTTL 10,000→8,986→7,970→6,954→5,938(hit마다 감소), 채운 뒤 10,086ms에 만료, 다음 조회 완성 상세 1회·재채움. 통과|
|JSON 형식 오류|`…ReadIntegrationTest$MalformedPayload.treatsMalformedPayloadAsMiss`|`not-json`, 필수 값 누락 JSON, 빈 문자열, JSON 리터럴 `null`|네 경우 모두 DB 값 응답, 완성 상세 1회, 올바른 JSON으로 재채움, 입력당 WARN 로그 1줄. JSON null 재현·수정·재검증은 [01](01-cache-performance.md#형식-오류-처리-보완--json-null-2026-10-15) 참조. 통과|
|DB 실패|`…$MalformedPayload.propagatesDatabaseFailure`|miss 경로 DB 조회 실패 주입|같은 예외 전파, 빈 값·0으로 숨기지 않음, key 미생성. 통과|

### 실제 앱·로컬 Redis에서 확인한 부분

관리자 API는 HTTP 관리자 로그인 없이 403이라([AdminBoundaryConfig](../../../apps/commerce-api/src/main/java/com/loopers/config/AdminBoundaryConfig.java)) 관리자 쓰기의 DEL은 실제 앱 HTTP로 실행하지 않았다. 대신 다음을 실제 앱(run3)·redis-master에서 확인했다.

- 상품 7의 기본정보를 채운 뒤(PTTL 9,909) 좋아요 등록 `201` → 상세 likeCount 5→6, 중복 등록 `LIKE_ALREADY_EXISTS`, 취소 `200` → 5, 다시 취소 `LIKE_NOT_FOUND`. 그동안 key 값은 바뀌지 않았고 PTTL은 9,909→9,646→9,420으로 줄기만 했다.
- 이어서 1초 간격 hit 6회 동안 PTTL은 8,251→2,574로 줄었고, key는 채운 뒤 약 10초에 만료됐다. 다음 조회는 PTTL 9,906으로 다시 채웠다.
- 장애 중 SQL로 직접 바꾼 가격이 재시작 뒤 AOF로 복원된 옛 key 때문에 만료 전까지 옛 값으로 보인 장면은 [03](03-redis-failure.md)에 있다.

## 결정적 경쟁과 복구 판정

늦은 fill은 우연한 동시 실행이 아니라 latch로 순서를 통제해 재현했다. 대기는 모두 유한하다(latch 5초, future 5초, 만료 대기 TTL+2초). executor는 `shutdownNow` 뒤 `awaitTermination(5s)`이 true인지 단언했다. 테스트 사이 DB는 `DatabaseCleanUp`으로 비우고, Redis는 `commerce:product-detail:v1:*` key만 SCAN·DEL한다(`ProductDetailCacheCleanUp`, FLUSHALL 미사용). 고객 상세를 읽는 기존 테스트 5개 클래스에도 같은 key 정리를 추가했다.

복구 판정은 "진행 중인 옛 fill이 끝남 → 옛 key가 실제 만료 → 다음 조회가 DB 원본(완성 상세 SQL)으로 응답하고 원본과 같은 값으로 다시 채움"이다. 늦은 fill은 SET 시점부터 새 TTL을 받는다. 그래서 옛 값은 DB 변경 시점이 아니라 **늦은 SET 시점**부터 최대 TTL 동안 보였다(측정 10,071ms). DB 변경 뒤 모든 요청이 TTL 안에 최신이 된다는 상한은 주장하지 않는다. 같은 key를 읽는 hit는 허용되지만 만료를 미루지 않았다. 삭제 비노출·과거 주문 금액은 옛 기본정보가 남아 있는 동안에도 DB 권위로 지켜졌다.

## 한계

- DEL 실패는 캐시 포트 spy가 예외를 던지는 방식으로 주입했다. 실제 Redis 장애 중 관리자 쓰기의 DEL 실패는 HTTP 관리자 경계 때문에 실제 앱으로 재현하지 않았다. 대신 [03](03-redis-failure.md)에서 장애 중 앱 밖 DB 변경이라는 같은 결과 조건을 관측했다.
- 늦은 fill은 단일 옛 읽기 1건만 재현했다. 여러 겹친 늦은 fill이 연속으로 TTL을 새로 받는 경우의 최대 노출 시간은 측정하지 않았다.
- 브랜드 이름 변경 중 동시에 생성된 상품, 또는 ID 조회 뒤 생긴 상품은 무효화 대상에서 빠질 수 있다. 이 경우 옛 이름이 TTL까지 보일 수 있으며 동시 경쟁은 검증하지 않았다. 삭제 비노출은 권위 확인으로 지켜진다.
- 수동 무효화는 구현 보류다.
