# W4 좋아요순 첫 페이지 — Redis + DB 버전 TDD

[전체 설계](../week2/commerce-erd-draft.md#w4-전체-좋아요순-캐시-설계) · [선택·대안](../week2/commerce-policy-decisions.md#w4-캐시-설계의-선택과-미정-질문) · [TDD 기대값](../week2/commerce-tdd-plan.md#w4-캐시-tdd-제안) · [스키마 적용](../week2/commerce-schema-operations.md)

2026-10-16 사용자가 Redis + DB 버전 방식의 구현을 승인했다. 기존 업무 계약·트랜잭션·비관적 잠금을 유지한다. **삭제 전 버전을 확인한 진행 중 조회는 이전 결과를 허용**하고, 삭제 완료 후 새 요청은 삭제 대상을 제외한다. local 프로파일의 상품 목록 Redis 캐시는 사용자 선택으로 기본 OFF다. 이 기록은 코드·테스트 증거이며 기술 글·PR 제출 완료를 뜻하지 않는다.

이 문서는 **최초 캐시 구현 당시의 기록**이다. 이후 [같은 버전 요청 합치기](product-list-single-flight-tdd-log.md), [좁은 좋아요 순위 집계](product-like-ranking-tdd-log.md), [TTL 5·30·60초와 키·메모리 비교](product-list-cache-ttl-memory.md)를 완료했다. 아래 SQL 2/4/1회·과거 측정값은 그대로 보존한다. 현재 비어 있지 않은 무필터 좋아요순의 DB-only/직접 계산 miss/hit는 **3/5/1회**이며, 최신 검사 상태는 [완료 체크리스트](../week2/commerce-completion-checklist.md#최신-통합-검사)를 따른다.

## 무엇을 바꿨는가

| 책임 | 구현·범위 |
| --- | --- |
| 대상 | 고객 `GET /api/v1/products?sort=likes_desc&page=0&size=20`, 브랜드 필터 없음. 다른 페이지·크기·정렬·필터·상세·관리자·개인 목록은 기존 DB 조회 |
| 조율 | `ProductQueryService`: 대상 확인 → 짧은 DB 버전 조회 → Redis GET → miss이면 DB snapshot → Redis SET. 외부 트랜잭션 안에서는 캐시 우회 |
| 원본 읽기 | `ProductListReader`: miss의 버전·전체 개수·상품/브랜드/좋아요 집계를 별도 RR 트랜잭션·동일 연결에서 읽음. 그 트랜잭션 종료 후 캐시 저장 |
| 캐시 | domain 포트 `ProductListCache`와 공개 DTO `ProductListSnapshot`, infrastructure의 `RedisProductListCache`. JPA 엔티티·개인별 liked·주문/포인트·관리자 필드 제외 |
| 버전 저장 | `ProductListRevisionRepository`, `product_list_revision(id=1, revision)`, 수동 SQL `008`. 업무 flush 후 원자적 upsert. 버전 증가에는 기존 트랜잭션 참여를 강제(MANDATORY) |
| 연결·시간 | 전용 master-only StringRedisTemplate, JSON, TTL 60초. connect/command 각각 100ms. 기존 RedisConfig의 다른 사용처는 변경하지 않음 |
| 장애 | GET 연결 오류는 DB 목록으로 우회하고 SET 생략. SET 실패는 올바른 DB 응답 유지. 손상 JSON은 invalid miss 후 재채움. DB 버전 실패는 옛 캐시나 빈 값으로 숨기지 않음 |
| 관찰 | `commerce.product.list.cache`: lookup의 hit/miss/invalid/error, store의 success/error. 비대상·OFF 요청은 lookup 지표에 포함하지 않음 |

키는 `commerce:{환경}:products:v1:brand=all:sort=likes_desc:page=0:size=20:rev={DB버전}`이다. namespace는 설정 가능하다. `v1`은 JSON 스키마, `rev`는 DB 변경 번호다. hit는 TTL을 연장하지 않고 값과 TTL을 하나의 SET으로 저장한다. 오래된 버전 키는 TTL로 정리하며 물리 DEL 성공에 최신성을 의존하지 않는다.

예를 들어 변경 전 버전이 42이면 조회는 `rev=42`를 사용한다. 관리자 변경과 함께 버전 43이 커밋되면 새 조회는 `rev=43`만 사용한다. 이전 요청이 뒤늦게 42를 저장하더라도 43의 결과를 덮어쓸 수 없다. **쓰기 성공 후 Redis를 호출하는 단계 자체가 없으므로**, DEL 실패를 가짜로 만들어 검증하지 않는다. 대신 Redis를 실제로 중단한 동안 DB 변경·버전 커밋과 조회 우회, 옛 키를 남긴 복구를 확인한다.

## 트랜잭션·락·영향 쓰기

아래 9개 경로와 실행 결과는 **W4의 POINT 결제 구현 당시 증거**다. W5 PG 전환에서 새로 연결할 재고 변경은 표 아래에 별도로 표시한다.

| 기존 변경 경로 | 목록 버전 증가 시점 |
| --- | --- |
| 관리자 상품 생성·이름/가격 수정·재고 설정·삭제 | 생성은 브랜드 잠금 후 새 상품 INSERT, 나머지는 기존 브랜드 → 상품 잠금. 업무 처리·응답 구성 뒤 마지막에 증가 |
| 관리자 브랜드 이름 변경·상품 일괄 삭제 | 이름 변경은 브랜드 잠금, 일괄 삭제는 브랜드 → 상품 ID 오름차순 잠금·bulk SQL 뒤 증가 |
| 좋아요 등록·취소 | 기존 사용자 → 상품 잠금·실제 관계 변경 뒤 증가 |
| 주문 확정 | 기존 주문 → 사용자 → 브랜드 ID → 상품 ID 잠금, 재고·포인트·주문 변경과 응답 구성 뒤 증가 |

총 9개 쓰기 경로가 참여한다. 기존 READ_COMMITTED·잠금 메서드·3초 DB 잠금 대기·충돌 오류 계약은 유지한다. 버전 증가 전 `flush`로 dirty checking의 업무 UPDATE를 먼저 실행한다. 버전이 먼저 커밋되거나 별도 REQUIRES_NEW로 반영되지 않는다. 실패하면 업무와 버전 모두 rollback한다.

**W5 연결 계획·미구현:** [선택한 저장 A안](../week2/commerce-policy-decisions.md#w5-예약-저장-구조-비교와-선택)의 OrderItem 예약 상태와 가용 재고를 TX A에서 함께 변경하고 목록 버전도 같은 TX에서 올린다. 확정 실패의 예약 반환 TX B도 같은 원칙을 따른다. 성공은 이미 확보한 예약을 소비하므로 추가 재고 차감·그로 인한 버전 증가가 없다. timeout·UNKNOWN·시간 한도 소진만으로 반환하거나 버전을 올리지 않는다. 기존 키·TTL 60초·local 기본 OFF·요청 시 채움은 유지하며, 이 연결의 원자성·rollback·삭제·장애/복구는 [W5-TDD-23](../week2/commerce-tdd-plan.md#w5-pg-결제-tdd-계획)에서 새로 검증한다. 아래 W4 테스트·호출 수·실측값을 W5 완료 증거로 사용하지 않는다.

같은 값 수정, 중복 좋아요, 없는 관계 취소, 재삭제는 증가하지 않는다. 삭제 상품에 남은 본인 좋아요 취소는 공개 목록을 바꾸지 않으므로 증가하지 않는다. 상품 없는 브랜드의 이름 변경은 보수적으로 증가시킨다. 포인트 충전·DRAFT 생성·브랜드 등록만으로는 증가하지 않는다.

새 비용도 있다. 서로 다른 상품 변경이라도 공통 버전 행에서 직렬화되며 그동안 기존 업무 잠금을 유지한다. 현재 단일 HTTP 유스케이스에서는 버전 갱신이 마지막이다. 앞으로 여러 변경 서비스를 하나의 외부 트랜잭션으로 조합하면 첫 버전 갱신 뒤 새 업무 잠금을 얻을 수 있으므로 최종 경계를 다시 설계해야 한다. 이 최초 증분에서는 Redis 분산 락·자동 재시도·stampede 제어를 추가하지 않았다. 이후 한 JVM의 진행 중 중복 계산을 줄이는 [요청 합치기](product-list-single-flight-tdd-log.md)를 구현했으며, 여러 서버 사이의 중복 계산 제어는 여전히 범위 밖이다.

## 실제 TDD와 검증 과정

| 단계 | 실제 관찰·처리 |
| --- | --- |
| Red | 조율 테스트에서 기존 서비스가 캐시를 사용하지 않아 실패. Redis 어댑터 빈 구현에서 miss/hit·TTL·JSON·지표 기대 실패. 버전 저장의 0/no-op 구현에서 증가·rollback 기대 실패. 9개 쓰기의 증가 누락과 버전 실패 rollback 기대 실패 확인 |
| 환경·테스트 오류 분리 | 첫 Spring 실행의 캐시 빈 누락, MANDATORY 프록시의 잘못된 spy 설정, Repository 예외 번역 wrapper, JSON LongNode/IntNode 비교 차이는 업무 Red로 세지 않음 |
| Green | domain 포트·JPA 버전·Redis 어댑터·조회 조율과 9개 쓰기 연결. rollback은 실제 업무 SQL·버전 SQL이 실행된 뒤 실패시키고 별도 DB 조회로 확인 |
| 실제 동시성 | RR의 버전 SELECT 직후/count 직후 다른 연결 변경 커밋, 옛 miss 채움 지연, 버전 확인한 hit의 삭제 경합을 래치로 결정적으로 재현 |
| 테스트 전제 교정 | 빈 metadata에서 동시 최초 upsert+첫 INSERT rollback은 MySQL 1213 발생. 운영 008의 선행 `(1,0)`을 concurrent fixture에 반영하고 커밋 6·rollback 2·최종 6 기대를 유지. 동시 초기화 문제를 해결했다고 주장하지 않음 |
| 실 Redis 장애 | 테스트 소유 Redis만 중단·같은 endpoint 재시작. 최초 테스트는 Docker의 임의 published port 재할당으로 실패하여 소유 loopback port를 고정하고 fresh inspect로 동일 endpoint 확인. 운영 timeout·재시도는 완화하지 않음 |
| 후속 검증 | HTTP·스냅샷·장애·성능은 구현 후 확대 검증이다. 처음부터 통과한 사례를 인위적으로 깨뜨리거나 전부 사전 Red였다고 기록하지 않음 |

## 어떤 테스트를 했는가

아래 경로는 `apps/commerce-api/src/test/java/com/loopers/` 아래다.

| 테스트 | 핵심 증거 |
| --- | --- |
| `application/product/ProductListCacheCoordinatorTest` | hit/miss·GET/SET 오류·대상/OFF·외부 TX 우회·DB 오류 전파 |
| `infrastructure/product/ProductListRevisionRepositoryIntegrationTest` | 실제 원자적 증가·TX 필수·overflow/SQL 후 rollback·flush 순서·RR·동시 커밋 6/rollback 2 |
| `application/product/ProductListRevisionMutationIntegrationTest` | 9개 경로별 증가, 업무 SQL 후 실패, 버전 경계 실패, no-op·거절·비대상 |
| `application/product/ProductListCacheSnapshotIntegrationTest` | 실제 RR·동일 연결의 버전/total/items. 중간 커밋에도 옛 버전+옛 payload, 다음 읽기는 새 버전+새 payload |
| `application/product/ProductListCacheApiIntegrationTest` | 실제 HTTP DB-only/miss/hit 전체 DTO 동일·SELECT 수, 비대상, 손상 값, 미커밋 게시 금지, 늦은 채움·삭제 hit, 1만 상품 측정 |
| `application/product/ProductListCacheMutationApiTest` | 9개 HTTP 변경 후 전체 DTO=새 DB 결과, 빈 페이지→생성, 캐시 밖 상품의 순위 진입/취소, no-op hit, 브랜드 삭제·과거 주문·접근/사용 제한 |
| `application/product/ProductListRevisionContentionIntegrationTest` | 다른 브랜드·상품을 실제 관리자 서비스로 변경, 공통 버전 행에서 대기, 2건 성공·재고 7/9·버전 2 |
| `infrastructure/product/cache/RedisProductListCacheTest` | 실제 JSON 형식·타입·범위·순위·중복·개인 필드 방어, TTL·오류·지표 |
| `infrastructure/product/cache/RedisProductListCacheIntegrationTest` | 실제 Redis의 miss→hit·옛 키 잔존·TTL 만료/비연장·손상 값·중단/재시작 |
| `infrastructure/product/cache/ProductListCacheOutageIntegrationTest` | 실제 앱·DB·Redis 중단 중 관리자 수정/삭제와 고객 우회, SET 실패, 같은 endpoint 복구·정확한 재채움/hit |
| `infrastructure/product/cache/ProductListCacheConfigurationTest` | 전용 master·독립 timeout·잘못된 설정·실제 local 기본 OFF/명시 ON |
| `infrastructure/schema/CommerceSchemaIntegrationTest` | 008 수동 적용·JPA validate·singleton/비음수·업무 데이터 보존·DDL 재실행의 버전 보존 |

기존 상품·브랜드·좋아요·주문·포인트·W3 동시성 테스트도 전체 검사에 포함한다. 기존 테스트는 직접 fixture SQL을 쓰므로 test 프로파일의 기본 캐시는 OFF로 유지하고, 새 캐시 통합 테스트는 명시 ON과 테스트 전용 namespace를 사용한다. 공유 Redis FLUSHALL은 하지 않는다.

## 성능 측정과 한계

동일 seed의 상품 10,000개·좋아요 46,955개·공개 상품 9,812개를 사용한다. 실제 HTTP 요청은 위 대상 경로 하나이며, 모드마다 워밍업 10회 뒤 순차 50회를 측정한다. DB-only는 캐시 사용만 꺼 동일 DB 목록을 호출하고, miss는 매 요청 전 테스트 소유 키만 제거하며, hit는 채운 키를 재사용한다. 준비 동작은 지연 측정 밖이다. 모든 표본의 전체 응답과 SQL 수를 assertion한다.

| 모드 | 요청당 SELECT | p50 | p95 | 의미 |
| --- | ---: | ---: | ---: | --- |
| DB-only | 2 | 53.58ms | 59.06ms | 전체 개수 + 전체 좋아요 집계/정렬 페이지 |
| miss | 4 | 57.86ms | 63.45ms | GET 전 버전 + 일관된 snapshot의 버전/개수/페이지, Redis GET/SET도 수행 |
| hit | 1 | 3.03ms | 3.48ms | DB 현재 버전만 확인, 비싼 집계/정렬은 수행하지 않음 |

2026-10-16 10:12 KST의 이 증분 최종 전체 검사에서 얻은 값이다. 이번 조건의 hit p50은 DB-only 대비 약 94.3% 감소했고 miss는 약 8.0% 늘었다. 원시 표본은 테스트 실행 시 `apps/commerce-api/build/reports/week4/product-list-cache-comparison.json`에 생성하며, 위 당시 값은 [고정 증거 JSON](evidence/product-list-cache-2026-10-16.json)에 보존한다. 생성 파일은 후속 재실행으로 달라질 수 있으므로 과거 수치 확인에는 고정 증거를 사용한다. 50개 값은 오름차순으로 저장했으므로 시간순 추세 자료는 아니다.

실제 Redis 중단 상태의 고객 DB 우회는 9ms, 관리자 수정·삭제와 고객 우회 전체는 46ms였다. SET 실패 응답은 테스트 컨테이너 중단 동작을 포함해 287ms였다. 이는 소규모 MockMvc 통합 실험의 표본이며 네트워크 HTTP 운영 SLO가 아니다. 각각 별도 JSON으로 생성한 결과를 같은 고정 증거에 보존했다.

서로 다른 상품을 변경하는 실제 관리자 서비스에서 첫 트랜잭션의 버전 잠금을 의도적으로 200ms 이상 유지하자 두 번째 버전 SQL은 약 219.75ms 걸렸다. 두 변경은 모두 성공하고 최종 버전 2·재고 7/9를 확인했다. 이 수치는 공통 행 경합의 재현 증거이며 자연 부하의 지연이나 처리량 측정은 아니다.

이 측정은 Hibernate 생성 스키마의 같은 데이터 비교이며 이전 수동 인덱스 실험과 직접 배수를 계산하지 않는다. 단일 클라이언트·쓰기 없음·모드 순서 고정·로컬 Docker 환경이다. 운영 적중률, 동시 부하의 처리량, TTL 60초의 최적성은 증명하지 않는다. miss는 DB-only보다 느릴 수 있어 hit 재사용량이 실제 이득을 결정한다.

## 남은 측정·운영 조건

- 홈의 실제 호출 조건·대상 비중과 운영 읽기/쓰기 비율은 아직 모른다.
- TTL 60초 고정의 읽기·좋아요 변경 혼합 비교는 Docker 복구 뒤 [실제 측정](product-list-cache-mixed-load.md)을 완료했다. 당시 변경 없음/초당 2회/10회의 hit율은 100/80.25/25.75%였다. 초당 2회는 중앙값 개선과 p95 악화가 함께 있었고, 초당 10회는 중앙값·p95·SELECT 수가 모두 나빠졌다. 이후 요청 합치기와 좁은 순위 집계를 개선했으므로 이 과거 값을 현재 구현의 결과로 대신하지 않는다.
- 당시 남았던 [5/30/60초의 여러 만료 주기·키 수·메모리 비교](product-list-cache-ttl-memory.md)는 완료했다. 이후 2026-10-16 사용자 승인으로 이번 과제의 TTL은 60초 유지로 확정했다. 브랜드 필터 없는 좋아요순 첫 페이지(`page=0,size=20`)를 요청 시 채우고 서버 시작 워밍은 미도입을 선택했다. [정책 결정](../week2/commerce-policy-decisions.md#w4-캐시-설계의-선택과-미정-질문)과 운영 최적값 검증은 구분한다. 실제 접근·변경 분포와 운영 메모리 예산은 아직 확인이 필요하다.
- 공통 버전 행의 의도적 대기는 재현하지만 운영 쓰기 지연/처리량이나 최대 트래픽을 보장하지 않는다. 잦은 변경은 TTL과 무관하게 hit를 줄인다.
- 로컬 기본 OFF는 재시작 시 DB 재생성과 옛 Redis 키의 버전 충돌을 피하기 위한 사용자 선택이다. 활성화 전 보존형 DB 설정·008·새 namespace 준비가 필요하다.
- OFF도 업무 쓰기의 버전 증가는 수행하므로 기존 DB에 새 코드를 배포하기 전 008이 필요하다. DB 초기화·복원·직접 SQL 변경과 namespace 전환 절차는 [스키마 안내](../week2/commerce-schema-operations.md)를 따른다.
- 합의한 진행 중 요청의 옛 결과 허용은 새 요청의 stale 응답 허용이 아니다. 주문 가격·재고·포인트·소유권 판단은 계속 DB에만 의존한다.

## 실행 결과

첫 통합 Green: 캐시 관련 **11개 스위트·116개 테스트**, 실패·오류·skip 0, 애플리케이션 main/test Checkstyle 통과, 종료 코드 0. 이후 local OFF 설정 회귀 1개와 측정 기록 보강을 추가했으며 최종 전체 검사 결과는 별도로 기록한다.

최종 전체 검사: **2026-10-16 10:12 KST, 74개 스위트·687개 테스트 통과**, 실패·오류·skip 모두 0. 신규 캐시 관련 11개 스위트 117개, 기존 스키마 검사에 추가한 1개를 포함한다. ArchUnit 통과, 전체 모듈 Checkstyle XML 11개·위반 0, **BUILD SUCCESSFUL / 종료 코드 0**. 전체 검사를 두 번 통과했으며 마지막 실행은 측정 JSON 저장 보강까지 포함한다. `git diff --check`와 수정 문서의 로컬 파일 링크도 확인했다.

후속 [혼합 부하 실험](product-list-cache-mixed-load.md)을 추가한 **11:41 KST 재검사에서는 76개 스위트·696개 테스트**, 실패·오류·skip 0, Checkstyle XML 11개·위반 0, ArchUnit 통과, 종료 코드 0을 확인했다. 위 단독 DB-only/miss/hit 수치는 10:12 실행의 역사 기록으로 유지하며 혼합 실험 수치와 직접 이어 비교하지 않는다.

```shell
./gradlew :apps:commerce-api:test --tests '*ProductListCache*Test' --tests '*ProductListRevision*Test' --tests 'com.loopers.infrastructure.product.cache.*Test' :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest --console=plain
./gradlew :apps:commerce-api:check --rerun-tasks --console=plain
```
