# 기능별 검증 범위·남은 위험 (W10)

실습 8번에 따라 여섯 기능의 확인 증거·미확인 위험과 관련 검사 결과를 정리한다. 사고 자체의 진단·복구·후속 결과는 `docs/verification/checkout-incident.md`에 있다.

검증 기준은 2절의 2026-11-26 최종 회귀다. 관측 원문 보존 보완을 포함한 최신 미커밋 코드의 실행 결과를 정리한다.

## 1. 기능별 검증 범위와 남은 위험

아래 회귀 근거는 2절(2026-11-26) 최종 check XML에 포함된 테스트다. 사고 수치의 제출 기준은 11월 26일 run이며, 보고서 2절의 같은 실행 증거로 연결한다. AUTO가 직접 다룬 범위와 기존 기능의 회귀를 구분하며, 모듈 전체 통과가 각 기능의 모든 운영 위험을 입증하지는 않는다.

|기능|이번에 확인한 증거|미확인 위험·범위 경계|
|---|---|---|
|상품·브랜드·좋아요·관리자|`ProductV1ApiE2ETest`, `BrandAdminV1ApiTest`, `LikeV1ApiE2ETest`, `AdminProductCommandIntegrationTest`, `ProductCacheReadIntegrationTest`·`ProductCacheInvalidationIntegrationTest` 등 기존 조회·관리·캐시 회귀. AUTO는 실제 상품 A/B의 주문·예약·활동을 연결|AUTO 중 삭제·관리 변경·캐시 장애를 유발하지 않았다. 운영 데이터 규모·다중 인스턴스의 캐시 보호를 새로 검증하지 않았다|
|포인트·주문·PG|`PointV1ApiE2ETest`, `PaymentRestartIntegrationTest` 등 기존 회귀. AUTO는 단일 시도·예약 유지, 같은 거래 조회 복구·반복 적용·소유권을 확인하고 포인트 잔액·이력 불변 대조|AUTO의 포인트 검증은 미사용 값 보존이며 결제와 포인트 혼합 사용을 새로 검증한 것이 아니다. 실 PG·실계정·모든 통신 장애는 미검증|
|이벤트·Outbox|`OrderOutboxDeliveryEndToEndIntegrationTest`·O11 회귀와 AUTO의 실제 relay→Kafka→streamer 활동·같은 사실 재발행 중복 방지|AUTO에서 발행 gap 장애를 추가로 만들지는 않았다. O11 회귀와 사고 복구의 후속 전달 검증을 구분한다. 운영 broker 장애·다중 인스턴스는 미검증|
|쿠폰|`CouponApiE2ETest`와 `CouponIssueTransactionIntegrationTest`의 Races 등 최종 check의 기존 발급·목록·경쟁 회귀. AUTO는 보유 쿠폰·캠페인 값 불변 대조|AUTO에서 쿠폰 발급 경쟁·사용을 일으키지 않았다. 공유 Redis 장애용 W8 opt-in 8건은 이번에 미실행이며 해당 장애 보호를 이번 결과로 주장하지 않는다|
|랭킹|`WeeklyRankingFlowTest`, batch의 `RankingJobRestartTest`·`WeeklyRankingBatchContractTest`·`WeeklyRankingLaunchTest` 등 회귀. AUTO는 복구 후 실제 활동 40행을 새 snapshot에 반영하고 공개 API A40/B20 확인|AUTO 자체는 같은 instance 재시작·revision 충돌을 재현하지 않는다(관련 기존 회귀와 구분). 운영 입력량·부하·다중 실행은 미검증|
|이번 사고|사고 검증 보고서 2절의 같은 20주문 baseline→fault→recovery, U별 세 가설 판정·fault 봉인·동일 거래 복구·create 추가 0·후속 효과·직접 health/Prometheus 원문|통제한 SUCCESS 저장 후 생성 응답 유실 1종이다. DB 장애·TX 시작 실패·프로세스 종료·운영 수동 재진입은 미검증. OBSERVED Lab은 제외|

## 2. 최신 코드 최종 회귀 (2026-11-26)

`./gradlew check --continue --rerun-tasks --no-build-cache`를 저장소 루트에서 1회 실행했다. 연결 검사를 따로 중복 실행하지 않았고 W8 opt-in은 활성화하지 않았다.

|항목|값|
|---|---|
|실행 시각·결과|2026-11-26 10:57:35~11:14:14 KST, 종료코드 0, BUILD SUCCESSFUL in 16m 38s, 52 actionable tasks 모두 executed|
|코드 기준|HEAD `c3fb22c` + tracked diff 74,062바이트(SHA-256 `dce9cf3d…411a`, 테스트 2파일·설계 문서 3파일) + untracked 코드 `CheckoutIncidentObservationTest.java`(SHA-256 `f259da1a…9e1e`). W10 run `metadata.json`의 diff hash·untracked hash가 실행 전 기록과 같고, 실행 뒤 코드 파일도 실행 전 사본과 같았다|
|환경|Java 21.0.12(Homebrew), Gradle 8.13, Docker. Kafka는 property/환경변수 미설정으로 기본 `localhost:19092`의 기존 공유 broker를 사용했다. MySQL·Redis는 테스트 컨테이너. heap 1g·`forkEvery = 30`은 그대로 유지|

|대상|tests|failures·errors|skipped|비고|
|---|---:|---:|---:|---|
|commerce-api 일반 test(XML 298개, 최상위 클래스 157)|1474|0|8|통과 1466. skipped 8은 W8 opt-in 2클래스(`CouponLocalRedisFaultIntegrationTest` 2, `CouponCacheLocalRedisFaultIntegrationTest` 6), **미실행**. 신규 `CheckoutIncidentObservationTest`·`ArchitectureTest` 포함|
|O11 `w6OutboxRecoveryTest`|1|0|0|전용 작업 실제 실행|
|AUTO `checkoutIncidentTest`|1|0|0|새 run `20261126T021136Z-bd52918d`, 44.6초. 결과는 사고 검증 보고서 2절|
|commerce-streamer / batch / pg-simulator|88 / 18 / 79|0|0||
|Checkstyle(api·streamer·batch·pg-simulator main/test)|—|위반 0|—|8개 보고서 모두 `<error>` 0|

modules·supports의 test는 NO-SOURCE다. 실행 로그에 `OutOfMemoryError`는 없었다. heap 최고치는 이번에 따로 측정하지 않았다.

**보존 위치(로컬, Git 미포함):** `/tmp/w10-final-regression-20261126-5f5wcZeE/`
- `state/`: 실행 전 시각·HEAD·git status·tracked diff/hash·untracked 목록, 코드 사본 hash, 실행 전 build 산출물 원본/사본 hash(180파일 일치), 보호 대상(기존 사고 run·증거 사본 645파일) hash, XML 집계
- `code/`: 실행 시점의 변경·untracked 파일 사본
- `before/`: 실행으로 덮이기 전 각 모듈의 test-results·reports(Checkstyle 포함)·jacoco와 사고 자식 로그
- `check.log`·`check-timing.txt`: 전체 출력과 실제 시작/종료 시각·종료코드
- `after/`: 새 test-results·reports·jacoco, 새 W10 run 전체 사본(원본과 hash 일치), streamer·batch 자식 로그

기존 사고 run 15개와 `docs/week10/verification/evidence/` 사본·manifest 645파일은 실행 전후 SHA-256이 같았다.

## 3. 공통 미확인 범위

- W8 공유 Redis 장애 opt-in 8건은 미실행이며 통과 수에 포함하지 않는다.
- 같은 코드의 전체 check 반복 안정성과 회수되지 않는 Spring context의 원인은 확인하지 않았다. heap 1g·forkEvery 30은 메모리 누적을 제한한 대응이며 원인 제거를 뜻하지 않는다.
- 로컬 통제 사고·관련 회귀 결과를 운영 배포·실계정·운영 규모의 지표 수집·부하·다중 인스턴스·모든 장애 검증으로 확대하지 않는다.
- 최신 코드 전체 결과는 2절의 11월 26일 실행이다. 그 뒤 코드가 바뀌면 이 결과를 그 코드의 근거로 쓰지 않는다.
