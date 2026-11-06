# 7주차 6주차 누락 재현 · 복구 실험 결과

7주차 설계 6장(D-111, D-112)의 실험 기록이다. 숫자와 ID는 아래 실행일의 **한 번 실행한 관찰값**이며, 다시 실행하면 시각 · offset 은 달라진다.

- 실행일: 2026-11-05, 로컬 Docker(Testcontainers `mysql:8.0` · `cp-kafka:7.5.3` 복제 1 · `redis`). 실험 코드 `apps/commerce-api/.../experiment/Week6GapRecoveryExperiment`
- 6주차 실행물: 커밋 `475fe3e4` 를 `build/week6-revision` 에 git worktree 로 꺼내 api · streamer bootJar 를 빌드(현재 저장소 wrapper 사용). streamer 는 6 · 7주차 코드가 같아 처음부터 끝까지 6주차 jar 하나를 쓴다
- 7주차 실행물: 현재 커밋의 api bootJar 를 local profile 로 띄워 운영 진입점(`order-outbox.command=backfill | run-once`)을 부르고 끝냄. 스케줄러가 대신 보내지 않게 `order-outbox.relay.enabled=false`
- 격리: 공유 MySQL 컨테이너 안의 별도 database `week6_recovery`, 별도 topic `order-events-week6-recovery`(partition 3), 별도 group. 준비부터 복구 끝까지 DB · broker · PG 상태를 초기화하지 않았다
- 정리: 자기가 띄운 프로세스 · worktree · database 만 지웠다

```bash
RUN_EXPERIMENT=true ./gradlew :apps:commerce-api:test --tests '*Week6GapRecoveryExperiment'
```

## 1. 준비: 6주차 코드로 누락 상태 만들기

시드(사용자 · 브랜드 · 상품 2개, 재고 각 10)는 SQL 로 넣었다. 6주차 관리자 API 는 관리자 인증 수단이 없어 항상 403 이고, 시드는 실험 대상 상태가 아니다. 주문 · 결제 · 확정은 모두 6주차 api 의 HTTP 경로(주문 → 확정 → PG SUCCESS → reconcile)로 만들었다.

**A 중단 방법 (설계 10장).** 6주차 api 를 `spring.kafka.producer.properties.linger.ms=30000` 실행 인자로 띄웠다. 6주차 발행은 확정 commit 뒤 결과를 기다리지 않고 send 하므로, record 는 30초 동안 producer 버퍼에만 있다. A 의 reconcile 응답(CONFIRMED)을 받자마자 api 를 강제 종료(`destroyForcibly`)했다. SIGTERM 이면 Spring 이 producer 를 닫으며 버퍼를 보내 버리므로 강제 종료여야 한다. 제품 코드는 바꾸지 않았다. 확정 commit 뒤 전달 전 프로세스가 죽은 한 장면이며 OS 장애 일반의 시험은 아니다.

비교해 고르지 않은 방법: proxy 차단은 client 가 첫 접속 뒤 broker 의 advertised 주소로 직접 붙어 우회되고, `docker pause` 는 이미 broker 소켓 버퍼에 들어간 요청이 unpause 뒤 기록될 수 있어 결과가 흔들린다. bootstrap 을 응답 없는 포트로 주는 방법은 확실하지만 "중단"이 아니라 `max.block` 전송 실패 장면이다.

| 주문 | 품목 | 확정 시각(UTC) | 6주차 발행 | 구매 활동 |
|---|---|---|---|---|
| B (orderId 1) | 상품 1 × 1 | 04:32:55.410514 | 약 30.6초 뒤 전달(linger). partition 0 offset 0 | streamer `RECORDED` → (1, 1) |
| A (orderId 2) | 상품 1 × 2, 상품 2 × 1 | 04:33:26.605262 | 확정 직후 api 강제 종료. 발행 로그 없음 | 0 |

강제 종료 뒤 linger + 5초를 기다리고 새 연결로 확인한 준비 상태:

| 확인 | 관찰 |
|---|---|
| 주문 | A · B 모두 `CONFIRMED` |
| 결제 · 예약 | A `TEST-TX-0002` · B `TEST-TX-0001` 모두 `SUCCESS` · `CONSUMED`, providerOrderId `LP-ORD-0000000002` · `LP-ORD-0000000001` |
| Outbox 테이블 | 없음 (`information_schema` 조회 0) |
| topic | key `1` record 1건뿐. A(key `2`) record 0건 |
| PG | create 누적 C = 2, 조회 2, 외부 거래 수 T = 2 |

6주차 코드만으로 "확정 · 결제 · 예약은 끝났지만 이벤트는 broker 에 없는" A 가 생겼다.

## 2. 복구: 7주차 backfill · relay

6주차 api 를 끝낸 뒤 같은 database · topic 에 7주차 api 실행물을 `ddl-auto=update` 로 붙였다. Outbox 테이블만 더해지고 6주차 행은 그대로 남는다.

| 순서 | 실행 | 종료 코드 | 관찰 |
|---|---|---|---|
| 1 | `backfill --order-ids=2,1` | 0 | A `RECORDED`, B `RECORDED` (둘 다 Outbox 가 없었음) |
| 2 | `run-once` | 0 | A → partition 2 offset 0, B → partition 0 offset 1. 두 행 모두 attempt 1 로 `PUBLISHED` |
| | streamer | | A `RECORDED`, B `ALREADY_RECORDED` |
| 3 | `backfill --order-ids=2,1` (다시) | 0 | A · B 모두 `ALREADY_RECORDED` (payload 비교 일치) |
| 4 | `run-once` (다시) | 0 | 보낼 행 없음. 10초 동안 추가 소비 없음 |

## 3. 확인 (설계 6.2 표)

| 확인 | 기대 | 관찰 |
|---|---|---|
| A | 구매 활동이 품목대로 새로 생김 | (상품 1, 2) · (상품 2, 1) |
| B | 재발행이 와도 활동 행 · 수량 그대로 | (상품 1, 1) 그대로. 소비 2회(`RECORDED` → `ALREADY_RECORDED`) |
| B 의 사건 동일성 | 6주차가 보낸 것과 같은 사건 | 6주차 record 와 7주차 재발행 record 의 value 가 **바이트까지 같음** |
| 두 번째 backfill · relay | Outbox 행 · 구매 활동 증가 없음 | Outbox 2행 그대로(`PUBLISHED`, attempt 1), 처리 기록 2행, record 3건 그대로 |
| PG | create = C(2), 복구 중 조회 · 추가 0, 외부 거래 = T(2) | create 2, 조회 2(변화 없음), 거래 2 |
| 주문 · 예약 | 재확정 0, 예약 추가 소비 0 | 주문 상태 · `confirmed_at` · version, 결제 상태 · 예약 · transaction key, 재고가 복구 전과 같음 |

- identity 는 저장된 사실에서 다시 유도했다. eventId 는 orderId, confirmedAt 은 `orders.confirmed_at`, items 는 주문 품목에서 왔고, B 에서 6주차 발행본과 같은 bytes 가 나온 것이 그 확인이다.
- 대상은 명시한 두 주문뿐이다. 다른 확정 주문을 소급하지 않는다.

## 4. 실행 중 발견

- **운영 진입점은 웹을 끄면 기동하지 못한다.** `--spring.main.web-application-type=none` 이면 `AdminBoundaryConfig` 가 요구하는 `HttpSecurity` 빈이 없어 context 가 실패한다. runner 주석의 실행 예시를 `--server.port=0 --management.server.port=0` 으로 고쳤다.
- **Windows 에서 worktree 디렉터리가 남을 수 있다.** 첫 실행 정리 때 등록만 지워지고 파일이 남아 다음 실행의 `git worktree remove` 가 실패했다. 실험 helper 가 자기 경로를 직접 지운 뒤 `worktree prune` 하도록 바꿨다.
- 실행 시간은 약 6분 30초(6주차 빌드 약 1분 10초, 프로세스 기동 각 20 ~ 25초, linger 대기 30초 × 2 포함).
