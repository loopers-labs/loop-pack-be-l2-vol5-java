# ADR 0055: DLT 전송은 기본 delivery timeout까지 기다린 뒤 멈춘다

Status: Accepted
Related: [ADR 0048](0048-stop-consumption-on-dlq-publication-failure-and-resume-manually.md), [ADR 0049](0049-connect-order-consumer-error-handler.md), [ADR 0051](0051-limit-producer-metadata-wait-and-keep-default-retries.md), [week6 설계](../week6/design.md#consumer-error-handler)

## Context

ADR 0048에 따라 DLT 전송이 실패하면 원본을 넘기지 않고 listener를 멈춘다. ADR 0051에서 공통 producer의 `retries`를 지워, 재시도 가능한 오류는 `delivery.timeout.ms`(기본 120초)까지 재시도한다. 이 설정은 DLT producer에도 적용된다.

[실험](../week6/kafka-settings-experiments.md#dlt-timeout-experiment)에서 DLT만 ISR 부족으로 만들면 약 120초 뒤 멈췄다. 그동안 consumer 스레드는 DLT 전송 결과를 기다리고 뒤 주문도 처리되지 않는다. 즉시 거절되는 실패와 달리 기다리는 시간이 길다. 이 시간을 받아들일지, DLT producer만 줄일지 정하지 않은 상태였다.

## Decision

- DLT producer의 delivery timeout을 따로 줄이지 않는다. 기본 120초(결과 대기 예산 125초)를 받아들인다.
- `setWaitForSendResultTimeout(10초)`는 최소 대기이며 상한이 아니다. 값은 바꾸지 않는다.
- 전송이 끝내 실패하면 ADR 0048대로 listener를 멈추고 원본 offset을 지킨다.

판단 근거는 두 비용의 비교다.

- 기다리는 비용: 메시지 문제와 DLT 장애가 겹치는 드문 경우에 뒤 주문이 최대 2분 늦는다. 구매 활동은 몇 분 늦어도 업무 영향이 작다고 이미 판단했다(ADR 0053).
- 멈추는 비용: 사람이 원인을 고치고 재시작해야 한다. 기다리면 리더 교체 같은 짧은 장애는 스스로 넘어갈 수 있다.

## Options considered

| 안 | 선택하지 않은 이유 |
| --- | --- |
| DLT producer의 delivery timeout을 30초로 줄이기 | 빨리 드러나지만 잠깐의 장애에도 멈출 수 있어 사람이 들어갈 일이 늘어난다. 원본을 지키는 것은 어느 쪽이든 같아 얻는 이득이 작다. 또한 `delivery.timeout.ms`는 `request.timeout.ms`(공통 20초)보다 짧게 둘 수 없다 |
| 실패해도 원본을 넘기기 | 메시지가 원본에서도 DLT에서도 사라진다. ADR 0048과 어긋난다 |

## Consequences

- 재시도 가능한 DLT 실패에서는 멈춤이 드러나기까지 약 2분이 걸린다.
- 주문 발행과 DLT 전송이 같은 시간 기준을 쓴다. 두 경로의 사정이 다르다는 점은 알고 받아들인다. DLT 장애가 잦아지면 다시 본다.
- 2분 대기는 주문용 `max.poll.interval.ms`(기본 5분) 안이다.

## Evidence and limits

- [DLT 지연 실패 실험](../week6/kafka-settings-experiments.md#dlt-timeout-experiment): 약 120초 뒤 중단, 원본 위치·DB 변화 없음, 복구 뒤 같은 group으로 재시작하면 같은 원본을 DLT로 보내고 이어 감.
- 이번 결정은 동작을 바꾸지 않는다. 새 실행 없이 기존 실험 결과를 근거로 한다.
- broker 무응답·네트워크 분할, 여러 consumer의 중단 범위는 확인하지 않았다.
