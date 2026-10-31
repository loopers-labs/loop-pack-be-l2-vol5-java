# ADR 0048: DLQ 전송 실패 시 소비를 중단하고 사람이 복구 후 재시작한다

Status: Accepted — 대응·재개 주체 선택, 업무용 error handler 연결은 ADR 0049
Related: [ADR 0041](0041-separate-invalid-messages-from-db-retry-exhaustion.md), [ADR 0047](0047-quarantine-conflicting-order-confirmation-facts.md), [week6 설계](../week6/design.md#dlq-failure-recovery)

Follow-up (error handler): [ADR 0049](0049-connect-order-consumer-error-handler.md)에서 재시도 분류·중단 범위·DLQ topic을 정하고 업무용 오류 처리기를 연결한다. 기존 결정은 Accepted를 유지하며 아래 본문은 당시 기록으로 보존한다.

## Context

메시지 형식 오류와 기존 사실 충돌은 DLQ로 분리하고, 전송 성공을 확인한 뒤 원본 offset을 진행하기로 했다. DLQ 전송도 실패할 때 중단할지 일시 정지·자동 재시도할지는 남겨 뒀다. 사용자는 사람이 수동으로 대응하는 방향을 선택했다.

기존 격리 실험은 DLQ topic의 크기 제한으로 실제 전송 거절을 만들고, 소비 중단·원본 위치 유지·제한 복구·같은 group 재시작 후 전송을 확인했다. 당시 중단은 테스트 조건이었고 이번 결정에서 업무용 방향으로 채택한다.

## Decision

- DLQ 전송이 실패로 판정되면 원본 record를 처리 완료로 넘기지 않는다. 해당 record를 지나가도록 offset을 commit하거나 구매 활동·처리 완료 기록을 만들지 않는다.
- 소비를 중단하고 원인과 원본 topic·partition·offset·group 등 복구에 필요한 식별 정보를 확인할 수 있게 한다.
- 사람이 실패 원인을 조사·수정하고, 같은 group으로 소비를 명시적으로 재시작한다. 자동 소비 재개용 반복 루프를 이번에 추가하지 않는다.
- 재시작한 consumer가 Kafka의 보존된 원본 record를 다시 읽어 DLQ 전송을 재시도한다. 사람의 기본 역할은 원인 해결과 재시작이며, 메시지를 수작업으로 복사하거나 offset을 건너뛰어야 하는 절차로 만들지 않는다.
- 원인이 남아 있어 전송이 다시 실패하면 원본 위치를 보호하며 다시 중단한다. DLQ 전송에 성공하면 기존 정책에 따라 해당 원본 위치를 진행하고 후속 record를 처리한다.
- producer 내부의 기존 전송 재시도·timeout은 이 결정에서 변경하지 않는다. 이번 수동 재개 선택은 DLQ 전송 실패 후 소비 경로의 복구에 관한 것이며, DB 재시도 횟수·간격은 유지한다.
- 구체적인 중단 범위와 배포의 재시작 정책, DLQ topic·보존·운영 재처리 및 실제 error handler 연결은 후속으로 구체화한다. DB 재시도 소진 뒤 재개 주체까지 이번 결정으로 자동 확대하지 않는다.

## Rationale and alternatives

원인이 해결되지 않은 상태에서 반복적으로 소비를 재개하면 같은 DLQ 실패가 이어질 수 있다. 중단·수동 재시작으로 원본 위치를 보호하고 문제를 확인한 뒤 진행한다. 중단 범위의 정상 record도 기다리므로 운영자가 원인을 확인하고 재개할 수 있어야 한다.

일시 정지·자동 재시도는 가용성을 높일 수 있는 대안이지만 간격·최대 대기·실패 관찰·최종 대응을 추가로 정해야 한다. 이번 시작 구성에는 채택하지 않는다.

## Evidence and limits

- [DLQ 발행 성공/실패 실험](../week6/kafka-settings-experiments.md#dlq-delivery-experiment): 실제 broker의 크기 제한 거절에서 DLQ 0건·원본 offset 0·DB 행 0을 확인했다. 제한 복구 후 같은 group으로 재시작해 DLQ 전달과 후속 정상 주문 처리를 확인했다. 이 실험의 ack mode는 RECORD다.
- 실제 주문용 listener/factory의 MANUAL_IMMEDIATE와 업무용 error handler를 결합한 DLQ 실패·수동 재시작은 아직 검증하지 않았다. 그 연결 뒤 원본 위치·동일 record 재수신·DLQ 결과·후속 주문을 함께 확인해야 한다.
- 전송 오류마다 DLQ에 저장되지 않았다고 단정하지 않는다. 전송 확인과 원본 offset commit이 원자적이지 않으므로 재전송 중복 가능성을 남긴다. 기존 크기 제한 거절의 관찰을 통신 장애 전체에 확대하지 않는다.
- 여러 consumer·앱 인스턴스의 재배정, 프로세스 자동 재시작, 원본 record의 보존 기간 만료와 DLQ 운영 재처리는 미검증이다.
- [선택 반영 기록](../week6/implementation-log.md#dlq-failure-manual-recovery). 이번 채택 문서화에서 새 테스트를 실행한 것으로 기록하지 않는다.
