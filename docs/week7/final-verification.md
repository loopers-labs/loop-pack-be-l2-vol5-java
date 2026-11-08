# W7 전체 검사와 증거 보완

2026-11-06, [공식 체크리스트](implementation-checklist.md)의 C11 전체 검사와 [W6 복구 기록](w6-outbox-backfill-tdd-log.md)의 실제 사건 식별자 보완을 함께 확인한다. 이전 증분 검사와 실패 기록은 유지하며 이번 실행 결과를 따로 적는다.

## 이전 전체 검사 실패와 수정

이전 전체 API 검사는 2,090개 중 63개가 실패했다. 보존된 `PgPaymentRecoveryApiE2ETest` XML에서 최초 Spring context 생성 실패의 원인은 `Failed to initialize pool: Too many connections`였다. 이후 같은 context를 요구한 테스트는 context failure threshold에 걸려 연쇄 실패했다. 관련 범위만 통과한 후속 검사는 이 전체 실패의 해결 증거로 간주하지 않는다.

| 관찰·선택 | 근거와 영향 |
| --- | --- |
| 하나의 테스트 MySQL을 여러 Spring context가 공유 | static `MySqlTestContainersConfig`를 사용하지만 각 context는 별도 Hikari 풀을 생성한다. 테스트 풀은 최대 10·최소 유휴 5이며 Spring context cache 기본 상한은 32다. |
| 실패 직전 종료되지 않은 풀 로그 24개 | 이전 XML의 풀 시작·종료를 시간순 대조한 값이다. DB에서 실측한 동시 연결 수와 동일시하지 않는다. |
| API 테스트 context cache 상한을 8로 제한 | [API 빌드](../../apps/commerce-api/build.gradle.kts)의 `tasks.test`에 JVM system property `spring.test.context.cache.maxSize=8`을 설정했다. 오래된 context가 제거되면 해당 풀도 종료된다. 새 context 생성 중의 일시적 풀까지 항상 8개라는 뜻은 아니다. |
| 8을 선택한 이유 | 보관하는 context의 풀 최대치 합을 80으로 줄여 새 context 생성 중 필요한 연결 여유를 둔다. 테스트 context 재생성 비용은 늘 수 있다. 향후 별도 풀·병렬 JVM을 늘리면 다시 검토한다. |
| 유지한 조건 | 테스트 풀 최대 10·최소 유휴 5, `maxParallelForks=1`, 동시 요청 수·timeout·assertion과 운영 풀 설정은 그대로다. MySQL 연결 한도 증설이나 테스트 제외로 해결하지 않는다. |

기존 전체 실패가 이 수정의 회귀 사례다. 설정 문자열을 그대로 확인하는 새 단위 테스트 대신 같은 전체 검사와 풀 종료 로그를 확인한다. 주문·결제·Outbox·relay·consumer 생산 코드는 이번 C11 보완에서 변경하지 않는다.

## W6 복구 기록 보완

`W6OutboxBackfillEndToEndTest`에서 준비된 두 주문 각각의 저장된 PG 주문 ID·거래 키·금액·최초 확정 시각·상품 ID별 수량을 기록한다. 실제 PG 생성 요청의 주문 ID·금액과 DB를 대조하고 원본 품목 수량도 assertion한다. 카드·HMAC·결제 행 전체를 출력하지 않는다. 실제 식별자와 준비/복구 호출 증분은 [복구 기록](w6-outbox-backfill-tdd-log.md)에 같은 실행의 값으로 남긴다.

## 전체 실행

```bash
./gradlew :apps:commerce-api:check :apps:commerce-streamer:check --rerun-tasks --console=plain
```

실행 로그: `/private/tmp/w7-final-check-20261106.log`. `--rerun-tasks`로 양 앱 검사와 의존 정적 검사를 다시 실행한다. 기존 별도 장시간 실험 태스크 `ttlMeasurement`는 이 `check`의 범위가 아니며 이번에도 제외 규칙을 변경하지 않는다.

| 확인 범위 | 이번 실행 결과 |
| --- | --- |
| commerce-api | **155개 스위트·2,091개 테스트 통과**, 실패·오류·skip 0 |
| commerce-streamer | **9개 스위트·99개 테스트 통과**, 실패·오류·skip 0 |
| 기존 실패 클래스 | `PgPaymentRecoveryApiE2ETest` 63개 모두 통과 |
| ArchUnit | 양 앱 `ArchitectureTest` 각 1개 실제 실행·통과. 위 테스트 수에 포함 |
| Checkstyle | 양 앱 main/test와 연결된 batch·공통 모듈·testFixtures를 포함한 XML 보고서 **12개·위반 0**. 코드 없는 NO-SOURCE 태스크를 검사 건수로 세지 않음 |
| Gradle 종료 | **BUILD SUCCESSFUL / 종료 코드 0 / 11분 28초**, actionable task 44개 모두 실행 |
| 소유 자원 정리 | 종료 후 Testcontainers 라벨의 실행/종료 컨테이너 0, 별도 streamer 자식·Gradle Test Executor 프로세스 0. 사용자 DB·기존 topic/group·볼륨을 변경하지 않음 |

API 2,091개와 streamer 99개를 합한 **2,190개**는 이번 실행에서 집계한 수다. 이전 실패 실행보다 API가 1개 늘어난 것은 그 뒤 추가된 `OutboxRelayKafkaOutageIntegrationTest`이며, 이번 수정에서 테스트를 제외하거나 skip으로 바꾼 결과가 아니다. 기존 별도 TTL 실험은 위에서 명시한 범위를 따른다.

풀 수명 로그는 API 테스트 JVM에서 **44개 시작·44개 정상 종료**를 보였다. 테스트 XML의 종료 37회와 Gradle 전체 로그의 JVM shutdown hook 종료 7회를 합친 값이다. 로그상 미종료 풀 최대치는 **9개**로, 캐시 8개와 새 context 생성·기존 context 제거 사이의 순간을 포함한다. 이는 실제 DB 연결 수 측정이 아니며 연결 한도 오류와 풀 종료 지연 경고는 재발하지 않았다.

검사 범위에는 W5 PG 결과 적용·예약·소유권·과거 POINT, 기존 재고/포인트 경쟁과 갱신 유실 대조군, W6 consumer 중복·실패 정책, W7 공동 TX·두 중단·실제 재발행·W6 보충·Kafka 장애 재개가 포함된다. 테스트별 동시 요청 수·기대값·대기 제한은 유지했다.

결과는 [API HTML 보고서](../../apps/commerce-api/build/reports/tests/test/index.html), [streamer HTML 보고서](../../apps/commerce-streamer/build/reports/tests/test/index.html)와 위 실행 로그에서 확인한다. build·임시 로그는 다음 실행에서 교체될 수 있으므로 범위·건수·원인·실제 사건 값은 이 문서와 W6 복구 기록에 남긴다.

## 최종 상태

남아 있던 **C11은 전체 회귀·정적 검사와 소유 자원 종료 증거로 충족**한다. C01~C10의 기존 구현·실험·운영 문서 근거와 합쳐 [공식 11항목](implementation-checklist.md)의 구현·검증을 완료로 정리한다. 단일 relay·단일 broker의 한계와 보존 정책은 [운영 안내](outbox-operations-retention.md)를 유지한다.

PR·기술 글 작성 및 실제 제출 여부는 확인하지 않았다. 이번 보완에서 커밋·푸시는 수행하지 않았다.
