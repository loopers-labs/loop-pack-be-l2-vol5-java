# 01 — 상품 상세 DB-only·miss·hit의 실제 이득

상태: **2026-10-15 1단계 구현 검증 실행 완료(기능·SQL 문장·읽은 행 관측), JSON null 형식 오류 보완·재검증 완료 / 성능 향상은 미검증 / 2026-10-16 Quest hit 조건 판정: 미충족, 현 정책 유지(아래 판정 절)**. Claude 실행 / Codex 원본 검토·글 반영. 기본정보 JSON만 cache-aside·좋아요 DB 조회·고정 TTL·일반 회귀 on·CPU 수집 제외가 최신 기준이다.

## 질문

기본정보 캐시가 어떤 원본 조회/조인/읽기 작업을 줄이며 좋아요 DB count·노출 권위 확인·Redis/JSON 비용은 얼마나 남는가? 목록 인덱스 실험과 별도 요청으로 기록한다.

## 실행 전 고정 — 구현한 구성

[상품 조회 설계 4.4](../../system-design/features/product-cache.md#44-상품-조회)를 따른다.

|항목|구현|
|---|---|
|key·값|`commerce:product-detail:v1:{productId}` → `{"name","brandName","price","stockQuantity"}` JSON String 하나. 좋아요 수·brandId·노출 여부는 담지 않는다|
|저장·조회|`SET key json EX ttl`(채울 때만 만료 지정), `GET`만 사용. hit는 TTL을 바꾸지 않는다|
|TTL|test 프로필·실험 실행 10초, 평시 기본 5분(`commerce.cache.product-detail.ttl`)|
|연결|기존 master 전용 `redisTemplateMaster`·`ReadFrom.MASTER` factory. connect·command timeout 각 200ms(`datasource.redis.*-timeout`)|
|miss|캐시 GET → 기존 완성 상세 SQL 1문장으로 응답 → 기본정보만 SET|
|hit|캐시 GET → 노출 확인·좋아요 수 SQL 1문장(기본정보 열을 선택하지 않음) → application에서 합성|
|DB 경계|고객 상세 Facade 메서드는 트랜잭션을 열지 않는다. Redis 대기 동안 DB 연결을 잡지 않으며 각 경로의 DB 조회는 단일 SELECT(autocommit)다|
|형식 오류|JSON 파싱 실패·필수 값 누락·빈 문자열·JSON 리터럴 `null`은 miss로 보고 WARN 로그를 남긴 뒤 DB 완성 상세로 응답하고 정상 JSON으로 다시 채운다. `null`은 2026-10-15 Codex 검토 뒤 보완했다(아래 절)|
|DB-only|`commerce.cache.product-detail.enabled=false`. 읽기는 기존 완성 상세 SQL만 사용하고 key를 읽거나 채우지 않는다. 쓰기의 commit 뒤 DEL은 유지해 다시 켰을 때 그 사이 변경된 상품의 옛 key가 남지 않게 한다. 이미 있던 key는 그대로 두고 TTL로 만료된다|

평시/일반 회귀는 on이다. DB-only는 이 비교의 앱 재기동 설정이며 서버 중단이 아니다.

## 비교 표 — 실제 앱·로컬 MySQL·Redis (HTTP, 순차)

조건: 2026-10-15 13:47–13:49 KST. 실제 앱 bootJar(local 프로필, `--commerce.cache.product-detail.ttl=10s`) + 로컬 Docker MySQL 8.0.46 `loopers` + redis-master 7.0.15. 자료는 소량 진단 seed(상품 10,000·좋아요 50,000)의 상품 7(좋아요 5). `X-USER-ID: 1`. 경로마다 준비 요청 20회 뒤 **50회** 측정. miss는 각 요청 전 해당 key 하나만 DEL(측정 시간 밖). 지연은 클라이언트에서 잰 HTTP 왕복(로컬 루프백). SQL 수는 앱 show-sql 로그를 요청 전후로 잘라 셌고, 읽은 행은 같은 구간 `performance_schema.events_statements_summary_by_digest` 증분이다.

|경로|HTTP SQL 총수/요청|기본정보 원본(완성 상세 SQL)|노출 확인·좋아요 SQL|Redis|문장별 rows_examined/요청|지연 ms min/p50/p95*/max/mean|동일 응답|
|---|---|---|---|---|---|---|---|
|DB-only (run2, enabled=false)|2 = 사용자 확인 1 + 완성 상세 1|1|(완성 상세에 포함)|없음|사용자 확인 1 · 완성 상세 6|5.31 / 6.83 / 7.75 / 9.12 / 6.76|50/50 동일|
|기본정보 miss (run1)|2 = 사용자 확인 1 + 완성 상세 1|1|(완성 상세에 포함)|GET 1 + SET 1|사용자 확인 1 · 완성 상세 6|6.39 / 7.00 / 7.55 / 9.75 / 7.07|50/50 동일|
|기본정보 hit (run1)|2 = 사용자 확인 1 + 노출 확인·좋아요 1|**0**|1|GET 1|사용자 확인 1 · 노출 확인·좋아요 6|5.44 / 6.72 / 7.59 / 9.12 / 6.76|50/50 동일|

\* p95는 50개 정렬값의 47번째 값.

세 경로 응답은 모두 `{"id":7,"brandId":8,"brandName":"brand-8","name":"product-7","price":359,"likeCount":5,"stockQuantity":7}`였다.

DB-only run2 직전 key 7에 가격 1인 옛 JSON을 넣어 두었다. DB-only 50회 응답은 모두 DB 값(가격 359)이었고, 그 key는 읽거나 덮어쓰지 않은 채 남아 있었다. 요청마다 보이는 `SET autocommit`·`READ ONLY`·`COMMIT` 문장은 사용자 확인(`UserFacade.requireExists`)의 readOnly 트랜잭션이다. 상세 SQL은 트랜잭션 밖 단일 문장으로 실행됐다.

### 자동 테스트의 같은 비교 (Facade 수준, Testcontainers MySQL·Redis)

`ProductCacheReadIntegrationTest$Modes.sameResultAndFewerSourceReadsOnHit`: 같은 상품에 대해 DB-only(스위치를 끈 경로) → miss → hit 순으로 호출하고 Hibernate가 준비한 SQL을 모았다. 결과는 DB-only 완성 상세 1, miss 완성 상세 1, hit 노출 확인·좋아요 1(완성 상세 0)이며 세 결과 record가 같다. 저장 JSON과 저장 직후 PTTL 9999ms도 확인했다.

## 판단 — 실제로 줄인 것과 남은 것

- **바뀐 것**: hit에서는 완성 상세 SQL(상품명·가격·재고·브랜드 이름을 선택)이 실행되지 않고(miss/DB-only 1 → hit 0), 같은 FROM·JOIN·WHERE·좋아요 서브쿼리에서 그 네 열만 빠진 확인 SQL이 대신 실행된다. 이것은 문장 이름과 선택 열의 변경이며 DB 원본 작업의 감소로 세지 않는다(아래 판정 절).
- **남은 것**: 노출 권위 확인과 좋아요 수를 위해 hit도 같은 Product PK·Brand 조건·좋아요 인덱스 범위를 읽는다. 이 자료에서 hit의 확인 SQL과 miss/DB-only의 완성 상세 SQL은 **모두 요청당 rows_examined 6**이었다. 상품 1행 + 좋아요 5행으로 해석하며, 브랜드 PK 조회가 이 값에 잡히지 않은 이유(상수 테이블 처리 여부)는 EXPLAIN으로 따로 확인하지 않았다. 완성 상세 SQL 1회가 확인 SQL 1회로 대체되므로 **전체 SQL 수는 줄지 않았다**. 기본정보 열의 선택·전송을 생략하지만 InnoDB에서 읽는 행 수 감소는 관측되지 않았다. 따라서 비싼 DB 작업 감소를 충분히 입증한 것으로 판정하지 않는다.
- **지연**: 세 경로의 p50 차이는 0.3ms 이내다. 로컬·소량 자료·순차 50회 조건의 차이는 측정 잡음 범위로 본다. hit가 HTTP 응답을 빠르게 했다는 성능 향상은 **검증하지 않았다(주장하지 않음)**. miss는 Redis GET+SET 왕복이 더해진다.
- 이 결과는 정책 선택(좋아요 DB 조회·hit 권위 확인 유지)의 비용을 보여 주는 원본이다. 이를 근거로 구조를 자동 변경하지 않으며, 해석과 글 반영은 Codex 단계에서 한다.

## 판정 — Quest hit 조건: 미충족 (2026-10-16, 현 정책 유지)

Quest 3번의 "hit는 비싼 원본 쿼리·집계 호출을 줄인다"를 현재 구현은 **충족하지 못한다**. 사용자가 정책을 바꾸지 않고(A) 미충족으로 기록하기로 했다. 새 측정 없이 위 원본과 SQL·DDL로 판정했다.

|작업|DB-only·miss 완성 상세|hit 확인 SQL|
|---|---|---|
|`product` PK 조회·`deleted_at` 조건|있음|있음|
|`brand` PK 조인·`deleted_at` 조건|있음|있음|
|`product_like` 상관 서브쿼리 COUNT|있음|있음|
|선택 열 `brand.name`·`product.name`·`price`·`stock_quantity`|있음|없음|

- 앱 로그의 실제 SQL에서 두 문장은 같은 PK 조건(`pm1_0.id=?`)·조인·서브쿼리를 쓴다. digest_text는 완성 상세 문장이 `id = ?` 앞에서 잘려 있어 앱 로그로 확인했다.
- DDL에 LOB 열이 없고 이름은 `varchar(100)`이다. InnoDB는 PK로 클러스터드 레코드를 통째로 읽으므로 열을 덜 선택해도 읽는 행·페이지는 같다. 관측된 rows_examined 6=6과 맞다.
- 상세에서 비싼 부분은 상품별 좋아요 COUNT 하나이고 hit에도 그대로 남는다. 좋아요가 많은 상품일수록 두 경로가 같이 커지므로 자료 규모·반복 횟수를 바꾼 재측정으로 결론이 바뀌지 않는다. 그래서 "증거 부족"이 아니라 "미충족"이다.
- 원인은 두 결정의 결합이다. PK 단건 상세를 캐시 대상으로 골랐고 좋아요 수를 DB에서 읽기로 했다. hit의 권위 확인이 필수이면 캐시가 줄일 수 있는 작업은 권위 확인 밖의 작업뿐인데, 지금은 그것이 선택 열 네 개다.

검토한 대안과 채택하지 않은 이유. 모두 정책 변경이므로 이번에는 적용하지 않았다.

|대안|hit에서 사라지는 DB 작업|정책 변경·추가 책임|
|---|---|---|
|좋아요 수를 기본정보 JSON에 포함, 확인 SQL에서 COUNT 제거|상품의 좋아요 COUNT|"좋아요는 DB 조회" 변경. 좋아요 등록·취소 commit 뒤 DEL, 좋아요 수 신선도 정책, 좋아요 경로의 DEL 실패·늦은 fill 검증|
|상품 `like_count` 비정규화|DB-only에서도 COUNT가 사라져 hit와의 차이가 다시 없음|이번 범위에서 제외한 결정. 상품 행 쓰기 경합|
|hit의 권위 확인 제거|전부|삭제 비노출 계약 위반. 선택 불가|
|목록 캐시로 대상 전환·추가|OFFSET 스캔·정렬·total COUNT|목록 캐시 제외 결정 변경. 삭제가 페이지 구성을 바꾸므로 revision이나 전체 무효화 필요|

revision이나 캐시 계층 추가는 그 자체로 DB 작업을 줄이지 않는다. 이 판정은 기능 정확성(동일 응답·변경 반영·장애 우회) 검증 결과를 바꾸지 않는다.

## 형식 오류 처리 보완 — JSON null (2026-10-15)

Codex 구현 검토에서 Redis 값이 JSON 리터럴 `null`이면 `readValue`가 null을 돌려줘 `toBase()`에서 NullPointerException이 나고, miss·DB 조회·재채움으로 가지 못한다는 누락이 지적됐다.

- **수정 전 재현**: 기존 `ProductCacheReadIntegrationTest$MalformedPayload.treatsMalformedPayloadAsMiss` 입력에 `"null"`을 추가하고 수정 전 코드로 `./gradlew :apps:commerce-api:test --tests '*ProductCacheReadIntegrationTest$MalformedPayload*'`를 실행했다. 2 tests 중 **1 failure**(errors/skip 0, BUILD FAILED)였다. 원인은 `NullPointerException … readValue(String, Class) is null`(`ProductDetailRedisCache.find:75` ← `ProductFacade.getProduct`)으로 지적과 같다. 고객 상세 요청이 DB 우회 없이 예외로 끝나는 결함이다.
- **수정**: 파싱 결과가 null이면 `IllegalArgumentException`을 던져 기존 형식 오류 catch(`JsonProcessingException | IllegalArgumentException`)·로그·miss 경로로 보낸다. NPE나 `Exception` 전체를 잡지 않으며, 캐시 정책·조회 구조·인덱스는 바꾸지 않았다.
- **수정 후**: 같은 테스트에서 not-json·필수 값 누락·빈 문자열·JSON null 4개 입력 모두 DB 값으로 응답하고, 완성 상세 SQL 1회로 정상 JSON을 재채움했으며, 형식 오류 WARN 로그가 입력당 1줄 남았다. DB 실패 전파 테스트도 통과했다. 관련 캐시 테스트 24건 0/0/0, 이어서 `cleanTest check` 377건 0/0/0, Checkstyle main·test, ArchUnit 3건 통과.

이 보완은 오류 처리 정확성에 관한 것이다. 위 비교 표의 결론(hit의 읽은 행 수 감소 미관측·성능 향상 미검증)은 그대로다.

## cold 동시 miss 관측

실험 대상 key만 비운 상태에서 같은 상품에 8개 스레드를 동시에 출발시켰다. 조회 공유·잠금·분산락은 구현하지 않았다.

|조건|테스트|기본정보 원본(완성 상세 SQL)|노출 확인 SQL|SET|결과|
|---|---|---|---|---|---|
|모든 miss 확인까지 통제: GET이 빈 결과를 돌려준 뒤 8개가 모두 barrier에 도착해야 진행|`ColdConcurrentMiss.everyControlledMissReadsSource`|**8**|0|8|8개 응답 동일. 이어진 warm 요청은 완성 상세 0·확인 1|
|동시 시작만 통제(start latch)|`ColdConcurrentMiss.observesUncontrolledConcurrentStart`|이번 실행 8(범위 1..8을 허용하는 관측)|0|-|8개 응답 동일|

동시 시작만 맞춘 경우의 miss 수는 실행마다 달라질 수 있다. 이번 실행에서 8이 나온 것을 고정 결과로 일반화하지 않는다. 좋아요 count는 miss·hit 모두 DB에서 집계하므로 cold 여부와 관계없이 남는다. 중복 억제 기능이나 장애 부하 용량은 검증 대상이 아니다.

## 한계

- 로컬 공유 자원(Docker Desktop MySQL/Redis, 같은 머신의 Gradle/IDE)에서 측정했다. 측정 구간에 다른 앱은 8080/8081을 쓰지 않았지만 다른 로컬 부하는 통제하지 않았다. CPU는 수집하지 않았다.
- 소량 진단 자료(상품 10,000·좋아요 50,000)와 상품 하나, 순차 요청 50회다. 승인 규모 D0, 동시 부하, 원격 네트워크 조건의 지연은 측정하지 않았다.
- digest 증분은 같은 시간대 MySQL 전체 문장을 집계한다. 측정 중 다른 클라이언트의 같은 digest 실행이 없다는 것은 실행 조건(다른 앱 없음)으로만 판단했다.
- Redis/JSON 처리 시간을 따로 계측하지 않았다. miss와 hit의 지연 차이에서 분리한 값도 없다.
