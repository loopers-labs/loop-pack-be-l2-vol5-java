# ADR 0052: commerce-api와 commerce-streamer에 MySQL 드라이버 timeout을 둔다

Status: Accepted
Related: [ADR 0049](0049-connect-order-consumer-error-handler.md), [ADR 0051](0051-limit-producer-metadata-wait-and-keep-default-retries.md), [week6 설계](../week6/design.md#db-driver-timeouts)

Follow-up (retry): 아래 "약 63초 뒤 중단"은 당시 재시도(1초·2초 두 번) 기준이다. 이후 [ADR 0053](0053-retry-transient-consumer-failures-for-minutes-before-stopping.md)에서 재시도를 간격의 합 5분으로 바꿨다. 한 시도는 여전히 약 20초 안에 예외로 끝나고, 한 간격(최대 30초)을 더해도 poll 사이가 `max.poll.interval.ms` 2분 안이다. 이 결정은 Accepted를 유지한다.

Correction: 아래 본문과 위 Follow-up의 `max.poll.interval.ms` 2분은 공유 batch factory의 값이다. 주문 listener는 기본 5분을 쓴다([실효 시간 확인](../week6/kafka-settings-experiments.md#dlt-timeout-experiment)). 아래 본문은 결정 당시 기록으로 보존한다.

## Context

ADR 0049의 오류 처리기는 **예외가 나야** 1초·2초 재시도와 listener 중단을 시작한다. DB 장애에는 두 종류가 있다.

- 연결 거부·deadlock처럼 바로 에러가 오는 경우: 드라이버가 예외를 던진다. 지금까지의 실패 실험은 모두 이 경우다(저장 포트에 예외 주입).
- DB가 멈추거나 네트워크가 끊겼다는 신호 없이 응답이 오지 않는 경우: MySQL 드라이버 기본값(`connectTimeout`·`socketTimeout` 0 = 무한)에서는 예외가 나지 않고 계속 기다린다.

두 번째 경우 streamer의 consumer 스레드는 재시도도 중단도 없이 멈추고, `max.poll.interval.ms`(2분)가 지나 group에서 빠지면 그 partition을 받은 다른 스레드도 같은 DB에서 멈춘다. 로그 없는 조용한 정지다. commerce-api는 요청 스레드와 DB 커넥션을 쥔 채 기다려, 커넥션 풀이 비면 다른 API까지 멈춘다.

## Decision

- 두 앱의 `application.yml`에 MySQL 드라이버 속성을 둔다. 공통 `jpa.yml`에는 두지 않아 긴 작업이 있는 batch에는 적용하지 않는다.

  | 속성 | 값 | 근거 |
  | --- | --- | --- |
  | `connectTimeout` | 3000ms | 같은 네트워크의 DB 연결은 보통 수 ms다. Hikari `connection-timeout`(3초)과 맞춘다 |
  | `socketTimeout` | 10000ms | 두 앱의 쿼리는 행 몇 개짜리 조회·저장과 짧은 행 잠금 대기다. PG 호출은 잠금을 쥔 TX 밖에서 한다(ADR 0030). 정상 처리보다 충분히 긴 여유를 둔다 |

- 응답이 없는 DB에서 예외까지의 상한은 **socketTimeout의 약 두 배(20초)**로 본다. 드라이버가 10초에 읽기를 포기한 뒤 연결을 정리하면서 한 번 더 최대 10초를 기다리는 것을 실행으로 확인했다.
- streamer에서는 최악의 경우 20초 × 3번 시도 + 대기 3초 ≈ 63초 뒤 listener가 멈춘다. `max.poll.interval.ms` 2분 안이다.

## Options considered

| 안 | 선택하지 않은 이유 |
| --- | --- |
| 기본값(무한) 유지 | 응답 없는 DB에서 재시도·중단이 동작하지 않고 조용히 멈춘다 |
| 공통 `jpa.yml`에 설정 | batch의 긴 작업도 같은 상한에 걸린다 |
| `socketTimeout` 더 짧게(예: 3~5초) | 행 잠금 경합이 몰릴 때 정상 대기까지 끊을 여유가 줄어든다. 측정하지 않은 상태에서 줄이지 않는다 |

## Consequences

- 10초를 넘는 정상 쿼리나 잠금 대기가 생기면 그 요청·처리도 실패한다. 이번 범위의 쿼리에서는 생기지 않는다고 보고, 전체 회귀로 확인한다.
- COMMIT을 보낸 직후 응답이 끊기면, 실제로 commit됐는지 모르는 채 예외가 난다. streamer는 재전달 때 같은 사실이면 no-op이라 결과가 안전하다. commerce-api는 이미 받아들인 "commit 뒤 발행 누락"과 같은 상태가 될 수 있다.
- `max.block.ms`(ADR 0051)와 함께, 브로커·DB 장애 때 요청이 스레드와 커넥션을 쥐는 시간에 상한이 생긴다.

## Evidence and limits

- [구현 기록](../week6/implementation-log.md#db-driver-timeouts): 두 앱의 실제 `HikariDataSource`가 드라이버에 두 속성(그리고 기존 `rewriteBatchedStatements`)을 넘기는지 설정 테스트로 확인했다. 설정 전에 실패하는 것을 먼저 확인했다.
- streamer의 실제 풀 커넥션에서 `SELECT SLEEP(30)`은 약 20초에 `SocketTimeoutException` 원인의 예외로 끝났다. 설정 전에는 같은 방식의 `SLEEP(15)`가 예외 없이 끝까지 기다렸다.
- 이 시간 측정은 Docker Desktop의 port 전달을 거치며, API 테스트와 같은 명령으로 함께 돌린 한 실행에서는 예외 없이 끝까지 기다렸다. 원인을 밝히지 못해 평소 `check`에서는 빼고 `experimentTest`로 둔다.
- 실제 DB 서버를 멈추거나 네트워크를 끊어 재현하지 않았다. 잠금 경합이 몰릴 때 대기 시간 분포도 측정하지 않았다.
