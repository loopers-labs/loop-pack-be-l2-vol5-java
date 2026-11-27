# W10 결제 사고 최종 검증

**2026-11-26 기준 W10 구현·검증 10항목 모두 충족. 세 앱의 199개 스위트에서 2,457개 테스트가 통과했다.** `volume-10`의 `cd455a3` 위에 있는 작업 트리를 검사했다. 테스트의 업무 시계는 11월 25일 03:00 UTC로 고정했다. 정상 주문 10건과 별도의 장애 실험 주문 10건을 만들고, 한 실행 안에서 같은 DB와 테스트 PG를 유지하며 각 주문을 랭킹까지 추적했다. [과제 원문](assignment-original.md) · [실행 체크리스트](implementation-checklist.md) · [사고 runbook](../runbook/week10.md)

## 전체 검사

저장소 루트에서 세 앱의 기본 `check`를 실행했다. `--rerun-tasks`와 `--no-build-cache`로 테스트와 정적 검사를 새로 수행하고, `--no-parallel`로 모듈 간 동시 실행을 제한했다.

```sh
W10_SOURCE_REVISION=cd455a390baa32af3242fd0237f9d44f5521bbe0+working-tree \
  ./gradlew --offline --no-parallel --no-build-cache --rerun-tasks \
  :apps:commerce-api:check :apps:commerce-streamer:check :apps:commerce-batch:check \
  --continue --console=plain
```

| 앱 | JUnit 스위트 | 테스트 통과 | 실패 | 오류 | 건너뜀 |
| --- | ---: | ---: | ---: | ---: | ---: |
| commerce-api | 175 | 2,215 | 0 | 0 | 0 |
| commerce-streamer | 9 | 99 | 0 | 0 | 0 |
| commerce-batch | 15 | 143 | 0 | 0 | 0 |
| **합계** | **199** | **2,457** | **0** | **0** | **0** |

Gradle 8.13은 **14분 44초**, 종료 코드 **0**으로 끝났다(`BUILD SUCCESSFUL`, 46개 작업 실행). 세 앱의 ArchUnit이 위 건수에 각각 1개씩 포함됐다. 새 Checkstyle XML 12개를 읽어 위반 0건을 확인했다. 세 앱의 main/test 6개와 JPA·Redis·Kafka·Jackson 및 testFixtures 6개다. 기본 검사에서 제외된 W4 TTL 측정과 W8 쿠폰 10만 건 부하 실험은 이번 실행 범위 밖이다.

## W10 원문 10항목 대조

아래 파일명은 사고 보존본 안의 경로다. 각 단계의 주문 ID와 시도 키는 같은 실행의 `cohorts.json`에서 찾을 수 있다. [채점 기준](grading-criteria.md) · [사고 연결 테스트](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/order/CheckoutIncidentTest.java)

| 항목 | 이번 사고에서 대조한 근거 |
| --- | --- |
| C01 증상 관측 | 실제 관리 HTTP에서 세 시점 모두 health `UP`, 유효한 UNKNOWN 표본은 `0 → 3 → 0`이었다. 장애 시점에 DB에서 읽은 주문 ID와 생성 호출·결과 적용 로그를 연결했다. 각 단계의 `health.json`, `prometheus.txt`, `boundaries.log`, `state.json`에 남는다. |
| C02 원인 구분 | `fault/diagnosis.json`에서 PG 지연, 승인 응답 유실, 내부 반영 실패의 예상을 비교했다. 같은 거래의 권위 조회는 `SUCCESS`였고 생성 호출에는 `NETWORK`, 로컬에는 `UNKNOWN` 커밋이 기록됐다. 이 세 주문에서는 성공 응답 유실 가설과 일치한다. 통신 오류가 발생한 물리 구간은 이 로그로 특정할 수 없다. |
| C03 관측 정보 보호 | 업무 게이지에는 고정된 `application` 라벨만 있다. 개별 주문은 허용 필드로 구성한 경계 로그에서 대사한다. 합성 결제만 사용하고 관리 HTTP는 테스트가 소유한 loopback 포트에서 확인했다. |
| C04 동일 환경과 집합 | `cohorts.json`의 두 집합은 각각 10건이고 겹치지 않는다. PG의 성공 저장 뒤 지정한 세 주문의 생성 응답 연결을 끊고 첫 confirm의 조회를 통제했다. 조치 전후에 같은 주문·시도 키·PG 거래를 추적했다. |
| C05 같은 주문 복구 | 장애 집합은 내부 확정 7·미확인 3·PG 성공 10에서 **확정 10·미확인 0·PG 성공 10**이 됐다. 전체 누적 PG 거래는 장애 시점과 복구 시점 모두 20건이다. |
| C06 추가 업무 효과 | 본인 주문의 reconcile이 기존 거래를 조회했다. 복구 중 PG create 증가는 0회이고 세 주문의 예약은 `HELD → CONSUMED`가 됐다. 가용 재고는 10개, 기존 포인트와 발급 쿠폰은 같은 값이었다. 동일 주문의 reconcile·callback 반복과 타인 접근도 검사했다. |
| C07 후속 결과 | Outbox 20행이 `PUBLISHED`가 됐다. Kafka 레코드 21건의 소비 결과는 최초 반영 20·중복 1이었다. 서로 다른 주문 20건의 최초 확정 시각·상품·수량이 주간 입력에 남았다. 공개 API는 같은 상품을 r1에서 10점, r2에서 20점·1위로 반환했다. `stream.json`, `ranking.json`에서 주문별로 확인할 수 있다. |
| C08 증거·재진입 | 조치 전 fault 원문 6개의 SHA-256과 보존본을 대조했다. 후속 발행 단계에서 멈춘 이전 실행도 원문대로 보존했다. runbook에 미종결 주문의 소유권·거래·금액을 대조한 뒤 재진입하는 절차, 결과 충돌·PG 미확정·원래 환경 종료 시 중단 조건을 명시했다. |
| C09 누적 회귀 | 세 앱의 2,457개 테스트와 ArchUnit이 통과했다. Checkstyle 위반은 0건이었다. 기능별 확인 범위와 남은 위험은 아래 표에 있다. |
| C10 자원 정리 | `finally`에서 응답 유실·조회 보류를 해제하고 테스트 PG와 소비자·배치 자식 JVM을 종료하는 경로를 확인했다. 전용 컨테이너와 실행별 Kafka topic·group을 사용했다. 종료까지 포함해 조회한 Testcontainers 컨테이너는 검사 전후 모두 0개였고, 종료 후 `jps -l`에는 조회 명령 자체만 남았다. 사용자 DB·공유 Redis·기존 topic/group은 사용하지 않았다. |

## 보존된 두 실행 읽기

| 최종 증거 ZIP 안의 경로 | 언제 무엇을 확인할 수 있나 |
| --- | --- |
| `incident/` | 이번 전체 검사에서 새로 만든 실행 `2026-11-26T05-06-48.743549Z-90d2336e-5340-4711-92af-89aed0661d30`. 정상 주문 1~10과 장애 집합 11~20을 처리했다. 12·15·19번을 같은 거래로 복구했고 12번의 동일 사건이 파티션 0의 offset 8과 10에서 각각 최초 반영·중복으로 처리됐다. 최종 group 커밋 위치는 파티션 0·1에서 각각 11·10이다. |
| `interrupted/` | 5단계 TDD 중 실제로 중단된 별도 실행 `2026-11-26T04-44-53.658508Z-057b1be2-fa68-4433-90a8-61d114f7f024`. `recovery/state.json`에는 주문 확정 20건과 `PENDING` Outbox 20행이 있고, `failure.json`에는 후속 단계의 assertion 실패가 있다. 당시 `verified.json`은 **결제 복구까지** 확인한 기록이다. |
| `checks.json` | 최종 Gradle 명령·종료 결과, JUnit 스위트별 테스트 수, Checkstyle 보고서별 위반 수, 실행 전후 자원 관찰값을 담았다. |

두 실행은 각각 자기 DB·PG와 run ID를 사용했다. 보존 시 각 실행의 fault 원문 6개 해시를 검산했고 ZIP의 전체 44개 항목을 원본과 대조했다. ZIP SHA-256은 `bdeca3426c5270277525bf28ac514ce239ca0094c919a2c09a38b4519f05bf87`이다. [중간 실패와 최종 실행 증거 ZIP](evidence/checkout-incident-stage6.zip) · [증거 검산 절차](../runbook/week10.md#증상과-시작-전제)

## 기능별로 확인한 범위

| 기능 | 회귀에서 확인하는 계약 | 남은 검증 범위 |
| --- | --- | --- |
| 상품·브랜드·좋아요·관리자 | 조회·삭제·접근 제한, 과거 주문 보존, 목록 캐시 무효화. [관리자 접근](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/commerce/AdminSecurityApiTest.java) · [Redis 장애 시 조회](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/product/cache/ProductListCacheOutageIntegrationTest.java) | 운영 데이터 분포와 트래픽에서의 지연·캐시 적중률 |
| 주문·재고·포인트·PG | 기존 POINT 기록, 재고·잔액 경합, PG 단일 발송·소유권. [DB 롤백 후 같은 거래 복구](../../apps/commerce-api/src/test/java/com/loopers/application/order/PaymentRecoveryRollbackIntegrationTest.java) | 실제 카드사나 상용 PG의 장애·대사 계약 |
| Outbox·구매 활동 | 주문·Outbox 공동 커밋, relay 중단·재발행. [소비 DB 커밋 뒤 재수신](../../apps/commerce-streamer/src/test/java/com/loopers/interfaces/consumer/OrderActivityOffsetRedeliveryIntegrationTest.java) | 운영 다중 브로커 장애, 보존 기한이 지난 레코드 |
| 쿠폰 | 마지막 한 장 경합, 발급 자리 제한, 고객별 목록. [작업자 종료 후 재개](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/coupon/CouponProcessLossRecoveryHttpIntegrationTest.java) | 10만 건 부하와 다른 API 지연은 W8 별도 실험 |
| 주간 랭킹 | 동일 instance 재시작과 체크포인트 롤백, 늦은 구매의 새 revision. [공개 중 실패와 이전 판 보존](../../apps/commerce-batch/src/test/java/com/loopers/application/ranking/WeeklyRankingPublicationIntegrationTest.java) | 운영 규모의 입력에서 배치 소요 시간 |
| W10 관측·복구 | 실제 DB 기반 업무 지표, 경쟁 가설, 본인 주문의 수동 복구에서 랭킹까지. [집계 실패·오래된 표본 처리](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/payment/monitoring/UnknownPaymentGaugeTest.java) | 운영 알림 전달, 관리망 접근 제어, 실제 통신 단절 지점 및 탐지 시간 |

## 제출 준비

사고 원문·재현 방법과 기능별 검증 범위를 PR에서 연결할 수 있다. 커밋·푸시와 PR/Playground 등록, W10 기술 글 링크 확인은 제출 작업으로 남긴다.
