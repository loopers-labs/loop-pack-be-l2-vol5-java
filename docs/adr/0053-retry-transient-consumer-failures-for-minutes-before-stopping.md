# ADR 0053: 메시지 문제가 아닌 소비 실패는 몇 분 동안 재시도한 뒤에만 멈춘다

Status: Accepted
Supersedes: [ADR 0042](0042-use-two-db-retries-before-stopping-consumer.md)(최대 2회), [ADR 0043](0043-increase-db-retry-delays-from-one-to-two-seconds.md)(1초·2초)
Related: [ADR 0041](0041-separate-invalid-messages-from-db-retry-exhaustion.md), [ADR 0049](0049-connect-order-consumer-error-handler.md), [ADR 0052](0052-set-mysql-driver-timeouts-per-app.md), [week6 설계](../week6/design.md#consumer-error-handler)

Implementation verification note: [실효 시간 확인](../week6/kafka-settings-experiments.md#dlt-timeout-experiment)에서 주문용 factory는 max.poll.interval.ms 기본 5분을 쓰고 공유 batch factory의 2분을 물려받지 않음을 확인했다. 한 대기 간격뿐 아니라 DB 처리·대기 등을 합한 실제 poll 사이 시간을 비교해야 한다. 아래는 결정 당시의 판단으로 보존하고, 현재 설명은 [설계](../week6/design.md#consumer-error-handler)를 따른다.

## Context

ADR 0042·0043에 따라 DB 오류 등 메시지 문제가 아닌 실패는 1초·2초 두 번 재시도한 뒤 listener를 멈췄다. 실패한 record를 건너뛰지 않는다는 원칙(ADR 0041)은 지켰지만, 재시도가 3초뿐이라 DB failover·네트워크 순단처럼 수십 초~몇 분이면 저절로 풀리는 장애에도 소비가 멈췄다. 멈춘 뒤에는 사람이 재시작해야 하므로, 일시 장애가 생길 때마다 사람이 들어가야 했다.

지키려던 것은 "건너뛰지 않는다"이고, 멈춤은 그 수단이었다. 건너뛰지 않으면서 일시 장애는 스스로 회복하게 할 수 있다.

## Decision

- 메시지 문제(형식 오류·사실 충돌)가 아닌 모든 소비 실패는 **간격을 늘리며 재시도**한다. 첫 간격 1초, 2배씩 늘리고, 한 간격은 최대 30초, 기다린 간격의 합이 5분이 되면 멈춘다.
  - 간격: 1 → 2 → 4 → 8 → 16 → 30 → 30 … 초
  - 5분 안에 풀리면 그 record부터 스스로 이어서 처리한다. 넘기면 지금처럼 listener 전체를 멈추고 offset을 지킨다(ADR 0049). 사람은 오래가는 문제(테이블 없음, 권한, 버그)에만 들어간다.
- 재시도 사이에는 consumer가 다시 poll한다. 그래서 **한 간격**만 `max.poll.interval.ms`(2분)보다 짧으면 group에서 빠지지 않는다. 총 대기(5분)는 이 제한과 무관하다. 한 간격의 상한 30초를 그래서 2분보다 충분히 짧게 둔다.
- 값은 `commerce.order-events.consumer.retry.*`로 설정한다. 테스트는 짧은 값을 넣는다.
- 5분은 DB failover(보통 수십 초~2분)보다 넉넉한 값이다. 구매 활동은 랭킹 입력이라 몇 분 늦어도 업무 영향이 작다고 봤다.
- 메시지 문제는 지금처럼 재시도 없이 DLQ로 보낸다. DLQ 전송 실패는 지금처럼 멈춘다.

## Options considered

| 안 | 선택하지 않은 이유 |
| --- | --- |
| 지금처럼 3초 재시도 뒤 멈춤 | 일시 장애마다 사람이 재시작해야 한다 |
| 멈추지 않고 끝없이 재시도 | 버그처럼 풀리지 않는 오류에서 조용히 영원히 막힌다. lag 알림 없이는 아무도 모른다 |
| 멈춘 뒤 자동 재시작 | 결과는 긴 재시도와 비슷한데 재시작 주체·주기를 따로 만들어야 한다 |
| DB 오류도 재시도 뒤 DLQ로 보내고 넘기기 | 그동안의 주문은 구매 활동이 빠지고, 사람이 DLQ를 다시 넣어야 한다. 실패 record를 건너뛰지 않는다는 원칙과 어긋난다 |
| 재시도 대기 동안 container pause | 한 간격이 `max.poll.interval.ms`보다 짧으면 필요 없다. 구성만 복잡해진다 |

## Consequences

- 일시 장애는 사람 없이 회복한다. 그동안 그 listener의 처리는 최대 몇 분 늦어진다.
- 처음 보는 오류(버그)도 5분 동안 재시도한 뒤에야 멈춘다. 멈춤이 드러나는 시점이 3초에서 5분 남짓으로 늦어진다.
- 응답 없는 DB에서는 한 시도가 드라이버 timeout(ADR 0052, 약 20초)까지 걸린다. 그래서 실제로 멈추기까지는 간격의 합보다 길다.
- 재시도 중 rebalance가 일어나면 재시도 추적이 새로 시작될 수 있다(실행으로 확인하지 않았다).

## Evidence and limits

- [구현 기록](../week6/implementation-log.md#long-consumer-retry): 실제 Kafka·MySQL과 실제 `@KafkaListener`에서 짧은 값(0.5초부터 2배, 한 간격 최대 2초, 합 10초)과 `max.poll.interval.ms` 4초로 확인했다.
  - DB가 6초 동안 실패했다가 돌아오자 listener를 멈추지 않고 그 record부터 저장했다. revoke 0회.
  - 계속 실패하면 9초 넘게 재시도한 뒤 멈추고 offset을 지켰다. 그동안 revoke 0회였다. 재시작하면 이어서 저장했다.
- 운영 값(5분, 한 간격 30초)으로 직접 돌리지는 않았다. 실제 DB 서버 중단·failover도 재현하지 않았다.
