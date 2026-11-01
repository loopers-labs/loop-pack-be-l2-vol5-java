# 상품 조회·캐시

기존 절을 기능별 기준 문서로 이동했다. 절 번호·계약·승인/검증 상태는 보존하며, 이동 자체는 새 설계 승인이나 실행 검증을 뜻하지 않는다.

읽기: [공통 API 계약](../common/api-contract.md)·[공통 모델](../common/domain-model.md)·[공통 의존](../common/architecture.md)을 함께 따른다.

## 4.4 상품 조회

상태: **2026-10-15 기본정보 JSON String·좋아요 DB 조회·고정 TTL·공통 afterCommit callback·cold 관측·회귀 on·CPU 제외·로컬 공유 확정 / 앱 구현·변경/실패/복구·회귀/check 완료(2026-10-15) / 목록 전후 반복 지연·전체 응답 비교 보완 필요(2026-10-16 E0로 완료) / 캐시 읽기량 감소·지연 향상 미입증(2026-10-16 Quest hit 조건 미충족 판정·정책 유지) / 라이팅 실험·글 미완료**.

### 4.4.1 대상과 보존 계약

목록의 브랜드 필터·latest/price_asc/likes_desc·page/size·동점 정렬·Product/Brand 삭제 조건·응답 필드·정확한 total은 [5장](../common/api-contract.md)을 유지한다. 목록은 캐시하지 않고 현재 스칼라로 측정한다. 목록 SQL은 바꾸지 않고 인덱스만 추가했다. 2026-10-16 라이팅 실험(D0)에서 고른 구성으로 정리해 product는 `(brand_id, price)`(가격순)·`(brand_id, created_at)`(최신순)·`(brand_id, deleted_at)`(브랜드별 total COUNT), product_like는 `(product_id)`(좋아요 수)를 둔다. 기존 `product(brand_id)` 단독 인덱스는 이 세 인덱스의 선두 범위와 중복돼 제거했다([4.1.6](catalog.md#416-인덱스와-설계-재검토-기준)). 좋아요 unique 인덱스는 유지한다. 가격순·최신순 목록은 정렬 없이 20항목을 읽고, `deleted_at`은 상품 행에서 Filter로 확인한다. 삭제 0% 자료의 관측이며 삭제가 많이 쌓인 경우의 읽기량은 측정하지 않았다. 2026-10-15 첫 구현은 `(deleted_at, brand_id, price)` 하나였고, 기존 "brand_id 선두 인덱스 하나" 검사를 유지하려고 고른 열 순서였다. Product 좋아요 수 컬럼의 비정규화는 성능·쓰기 책임을 보고 결정한다. 좋아요순은 전체 후보의 순위를 정한 뒤 페이지를 고른다. keyset은 SQL 비교만 하며 API를 교체하지 않는다.

고객 상세의 상품명·Brand 이름·가격·재고는 하나의 JSON String으로 cache-aside한다. 좋아요 수는 DB에서 조회·집계하며 별도 count cache를 두지 않는다. key는 `commerce:product-detail:v1:{productId}`, 값은 `{"name","brandName","price","stockQuantity"}`다. id는 요청 경로, brandId는 매 조회의 DB 확인 결과에서 가져온다. domain 포트 `ProductDetailCache`(값 `ProductDetailData.CacheValue`)를 infrastructure `ProductDetailRedisCache`가 구현한다. Redis Hash·count INCR/DECR·Lua·Redis 원본→DB 주기 반영/배치는 도입하지 않는다.

관리자 생성/수정 응답·목록·내 좋아요 목록에는 상세 캐시를 확대하지 않는다. 삭제 비노출·소유권·주문 금액/재고/포인트·과거 주문 보존은 완화하지 않는다. 상세 hit에도 Product/Brand 노출의 권위 확인을 유지하고 주문 판단은 원본 DB로 수행한다.

### 4.4.2 읽기 흐름과 책임

기본정보 hit는 재사용하고 miss면 DB 원본 조회·고정 TTL 저장 후 응답한다. 좋아요 수는 hit/miss와 무관하게 DB에서 구한다. application이 기본정보·DB 좋아요 수·권위 확인 결과를 합성하며 infrastructure가 RedisTemplate·JSON 변환·연결을 담당한다.

구현은 캐시 GET을 먼저 한다. miss는 기존 완성 상세 SQL 한 문장(노출 조건·좋아요 집계·기본정보)으로 응답하고 기본정보만 채운다. hit는 `ProductQueryRepository.findVisibleWithLikeCount` 한 문장(결과 `ProductDetailData.DbResult`)으로 Product·Brand 미삭제 조건, brandId와 DB 좋아요 수만 읽는다(기본정보 열 미선택). 고객 상세 Facade 메서드는 Redis 대기 동안 DB 연결을 잡지 않도록 트랜잭션을 열지 않으며, 두 경로 모두 단일 SELECT라 commit된 값만 채운다. hit의 DB 호출 0회를 목표로 계약을 완화하지 않는다. 실제 줄인 기본정보 조회/조인과 남는 count/권위 확인을 측정한다. 완성 상세 SQL을 매번 전부 수행한 뒤 같은 값을 캐시에서 다시 읽는 구조를 source 감소로 세지 않는다.

JSON 형식 오류는 miss·DB 재조회·올바른 재채움·로그로 처리한다. 정상 형식의 오래된 값은 형식 검사로 알 수 없다. DB 실패는 기존 오류이며 빈 값/0으로 숨기지 않는다.

평시·일반 업무 회귀는 cache on이다. 캐시 경로의 DB-only/miss/hit 성능·정확성 비교에 필요한 경우만 스위치로 읽기/채움을 우회한다. 일반 테스트를 모두 off/on으로 반복하지 않는다. off(`commerce.cache.product-detail.enabled=false`, 앱 기동 설정)는 기본정보 읽기·채움만 우회해 기존 완성 상세 SQL로 응답한다. commit 뒤 DEL은 유지해 다시 켰을 때 그 사이 변경분의 옛 key가 남지 않게 하며, 이미 있던 key는 지우지 않고 TTL로 만료되게 둔다. cold는 기본 cache-aside와 통제된 동시 miss 관측만 하며 중복 조회 공유·잠금은 도입하지 않는다.

### 4.4.3 변경 책임과 commit 이후 처리

기본정보 영향 쓰기는 DB commit 성공 뒤 해당 JSON key를 동기 DEL한다. Product 수정/삭제, Brand 변경/bulk 삭제, 관리자 재고 변경·주문 확정의 재고 변경 등 실제 영향 경로/여러 상품 key를 확인한다. 좋아요 관계 변경만으로는 기본정보를 무효화하지 않는다. 좋아요 등록/취소·중복·없는 취소·rollback은 기존 DB 계약을 유지하고 조회의 DB count로 검증한다.

각 업무는 실제 TX 안에서 영향 상품/작업을 공통 afterCommit callback 도구에 전달한다. 공통 도구가 Spring transaction synchronization에 등록하고 실제 commit 뒤 같은 요청 흐름에서 동기 실행한다. rollback에서는 실행하지 않는다. 별도 캐시 AOP·AFTER_COMMIT 이벤트는 추가하지 않는다. 공통화가 업무별 영향 key를 자동 발견하는 것은 아니다. 도구는 `support.transaction.AfterCommitRunner`이며, 실제 트랜잭션 밖 호출은 commit 시점을 정할 수 없으므로 `IllegalStateException`으로 거절한다. Product 수정·삭제·최종 재고는 해당 상품, Brand 이름 변경은 같은 TX에서 조회한 그 Brand의 미삭제 상품, Brand 일괄 삭제는 bulk 전에 조회한 미삭제 상품, 주문 확정은 재고를 바꾼 상품을 등록한다.

commit 후 작업은 HTTP 응답 전 완료 또는 제한 대기/실패 처리를 마친다. cache 후처리 오류만으로 DB 성공을 실패로 바꾸지 않도록 공통 경계에서 로그를 남긴다. 별도 재시도·재처리 worker·배치는 없고 미commit 공용 cache를 게시하지 않는다. DB 실패 자체를 성공으로 바꾸지는 않는다.

### 4.4.4 장애 계약과 표시 최신성·복구

|조건|계약|
|---|---|
|정상 변경 뒤 새 순차 조회|장애·동시 fill 없는 조건에서 변경 결과 반환|
|DB rollback|원본 유지·미commit cache 비게시|
|Redis 오류·DB 정상·통제 부하 가능|제한 대기 뒤 DB 원본 우회|
|DB도 실패|기존 오류 유지|
|JSON 형식 오류|miss·DB 재조회·올바른 재채움·로그|
|후처리 실패·늦은 fill|옛 기본정보를 실제 만료까지 허용. 고정 TTL과 다음 DB miss 재채움으로 복구 검증|
|삭제·소유권·주문 판단|기존 DB 보호 계약 유지|

TTL은 시험 10초·평시 5분의 고정값으로 저장 시 설정하고 정상 hit에서 연장하지 않는다. 별도 최대 보존 metadata는 두지 않는다. 늦은 읽기가 새 TTL로 옛 값을 저장할 수 있으므로 DB 변경 시점부터 무조건 5분이라는 전역 최신성 상한은 아니다.

늦은 fill 검증은 옛 DB 읽기 → 변경 commit/후처리 → 옛 SET → 새 조회를 래치로 재현한다. 진행 중 옛 fill을 모두 종료한 뒤 실제 만료·다음 DB miss·원본 일치/재채움을 확인한다. 반복 hit가 만료를 미루지 않는지도 확인한다. 실제 만료 뒤 miss 재채움은 별도 배치/재시도가 아니다. 정상 DEL 경로는 유지하며 수동 무효화는 구현 보류다.

### 4.4.5 연결·설정과 비용

기존 master 전용 String RedisTemplate/factory를 우선 사용한다. 새 구성은 필요 근거가 생길 때 검토하고 replica/복제 지연 실험은 제외한다. 초기 connect/command 각 200ms는 정상 GET/SET/DEL·연결·장애 대기 실측으로 확인·조정하며 HTTP 전체 상한으로 쓰지 않는다.

기본정보 DB-only/miss/hit의 실제 원본 감소, JSON 처리·Redis 왕복·DB 좋아요 count·권위 확인·후처리를 나눠 관측한다. hit TTL 갱신과 별도 count 캐시의 부분 miss 비용은 없다. cold의 기본정보 원본 중복과 항상 남는 count를 구분한다.

### 4.4.6 검증·상태·관련 기록

진행 순서는 구현과 아래 필수 검증 완료 → 구현 뒤 SQL-only 라이팅 실험 → 글 작성이다. 변경 전 SQL/DDL/코드 복원 자료는 보존하되 대규모 후보 실험/본문 작성을 구현의 선행 조건으로 두지 않는다. SQL-only는 후속 글 실험의 범위이며 실제 앱/DB/Redis의 변경·실패·복구 검증을 대체하지 않는다. 후속 SQL 결과/시간을 HTTP 전체 성능으로 표현하지 않는다.

기존 로컬 Docker MySQL/Redis + IntelliJ·현재 loopers를 함께 사용한다. 별도 DB/Compose를 필수로 만들지 않는다. Quest의 격리된 실습 DB 조건과 달리 공유 사용을 선택했으므로 동시 작업·측정/장애 구간·한계를 명시하며 전용 격리 환경이었다고 하지 않는다. 최신 합의대로 별도 schema·복제/테스트 테이블 없이 실제 loopers에서 비교한다. 실험 대상으로 명시한 비제약 조회용 인덱스만 보존 DDL에 따라 일시 전환하고 시작 구성으로 복구한다. PK/unique/필수 FK·관계없는 인덱스와 자료는 유지한다. ddl-auto 재시작과 실험 key/worker 정리, 장애 뒤 선택한 로컬 Redis의 사용 가능 상태 복구를 확인한다. 관계없는 컨테이너 중단·FLUSHALL은 하지 않는다.

DB warm·현재 scalar·후보/누적 인덱스·작은 트리·집계·페이징·단건/묶음 쓰기·공간을 비교한다. 기본 규모는 상품 100,000·브랜드 100·사용자 20,000·좋아요 1,000,000으로 승인됐다. 확정한 읽기 표본·삭제 비율과 아직 제안인 쓰기 묶음 조건은 실행 계획에서 구체화하고 최종 DDL은 실측 후 선택한다. seed SQL의 MySQL 설치/실행은 미검증이다.

실제 앱/MySQL/Redis에서 기본정보 DB-only/miss/hit·형식 오류, 필수 변경/rollback/후처리 실패/늦은 fill/Redis 중단·재시작 8장면을 검증한다. 좋아요는 기본정보 hit 상태에서도 DB 변경 count가 반영되는지 확인한다. 일반 업무 회귀는 cache on으로 실행하고 Checkstyle·ArchUnit/check를 유지한다.

앱·DB·Redis CPU 수집은 모두 이번 범위에서 제외한다. 기존 Actuator/Micrometer 등 모니터링 구성은 변경하지 않는다. 지연/오류·실제 읽기량/loops·집계/정렬·쓰기 시간·공간으로 판단하며 CPU 효율/포화·최대 처리량을 검증했다고 쓰지 않는다. 실제 버전·자원 할당·다른 로컬 부하는 기록한다.

실행 결과와 한계: [상품 조회·캐시 기록](../verification/core-features.md#상품-조회캐시--2026-10-1516-실행-기록).
