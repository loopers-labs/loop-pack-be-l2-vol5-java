# 주문 확정 Outbox와 단일 Relay 설계 (W7)

> **주문 확정과 전달할 사실을 한 DB 트랜잭션에 함께 남기고, relay가 broker 확인 뒤에만 "발행됨"으로 기록한다. 중복 발행은 막지 않고, W6 소비자의 멱등 처리로 같은 구매 활동에 수렴시킨다.**<br>
즉, 재시도하려면 "무엇을 다시 보내야 하는지"가 DB에 남아 있어야 한다.

W6의 "commit 뒤 직접 발행"에서 commit과 send 사이에 멈추면 확정 주문은 있는데 이벤트가 없다. 이 빈틈을 Outbox로 없앤다.
## 발제의 핵심 주제

consumer의 중복 제거는 **도착한 record**만 지킨다. producer가 한 번도 보내지 못한 이벤트는 찾지 못한다. 발행 위치를 바꿔도 DB와 Kafka를 하나의 저장소처럼 만들 수 없다.

| 발행 위치 | 중단·실패 | 남는 문제 |
|---|---|---|
| DB commit 전에 send | broker 기록 뒤 확정 rollback | 확정되지 않은 주문의 구매 활동 |
| DB commit 뒤에 send (W6) | commit 뒤 send 전 중단 | 확정 주문은 있는데 구매 활동 없음 |
| 메모리 queue에 보관 | process 종료 | 재시작 뒤 전달 대상 복원 불가 |

| 발제 주제 | 질문 | 이번 설계의 답 |
|---|---|---|
| 원자성 | 사실을 어디에 남기나 | 확정 TX 안에서 Outbox를 PENDING으로 insert. 같이 commit, 같이 rollback |
| Relay | 어디까지 했으면 완료인가 | broker 성공을 확인하고 PUBLISHED를 저장했을 때. 소비 완료가 아니다 |
| 재처리 | 응답을 잃거나 상태 저장 전에 멈추면 | PENDING이 남아 같은 eventId를 다시 보낸다 |
| 수렴 | 두 번 발행되면 | W6 소비자의 eventId·orderId 유일 제약으로 구매 활동은 한 번 |
| 운영 판단 | 어떻게 구분하고 다시 처리하나 | PENDING·PUBLISHED·소비 완료를 따로 보고, 미해결 행은 삭제 없이 같은 행으로 재개 |

## 이번 과제의 범위

| 유지 (W6) | 추가·교체 (W7) |
|---|---|
| PG 결과 검증·예약 소비·CONFIRMED | 같은 확정 TX의 Outbox insert |
| eventId·orderId·confirmedAt·items, Avro 계약 | 변하지 않는 key·payload를 DB에 저장 |
| topic·소비 group·구매 활동 consumer | 단일 polling relay·전송 상태 |
| 소비 중복 방지·DB TX 뒤 ack | 중단·재시작·W6 누락 복구 검증 |

다중 relay, CDC, 새 결제수단·주문 도메인은 범위 밖이다.

## 핵심 결정

| 결정 | 이유 |
|---|---|
| `PaymentStatusUpdater`의 최초 확정(`succeed`) 안에서 Outbox를 insert하고, Facade의 commit 뒤 직접 send는 제거한다 | 확정·예약 소비·Outbox가 한 TX라 둘 중 하나만 남을 수 없다. `REQUIRES_NEW`나 after-commit insert로 나누면 같은 빈틈이 돌아온다. callback·reconcile은 같은 경로를 지나므로 Outbox도 한 행이다 |
| payload는 확정 TX 안에서 Avro bytes로 만들어 저장하고, relay는 저장된 key·payload를 그대로 보낸다 | relay가 현재 상품을 다시 읽으면 재발행 때 과거 사실이 바뀐다. OSIV가 꺼져 있어 commit 뒤에는 품목을 읽을 수도 없다 |
| eventId는 W6 규칙(`order-confirmed:v1:{orderId}`)을 유지하고 Outbox에 유일 제약을 둔다 | 재발행·복구·타임아웃 어느 경우에도 같은 사건이다. 새 eventId로 회피하면 소비 중복 방지가 깨진다 |
| relay는 단일 루프다. 짧은 조회 TX → TX 밖 send와 future 성공 확인 → 별도 TX로 PUBLISHED. 실패·timeout은 PENDING 유지 + 다음 시각·오류 기록 | send 대기 동안 DB 연결과 잠금을 쥐지 않는다. PUBLISHED를 send보다 먼저 저장하면 중단 시 미발행 사건이 완료로 숨는다. 늦게 저장하는 쪽은 중복이 생길 뿐 다시 찾을 수 있다 |
| W6 누락 복구는 확정 주문의 저장 사실로 Outbox가 없을 때만 만든다. PG는 다시 부르지 않는다 | 기존 eventId·confirmedAt·품목을 그대로 써야 같은 사건으로 소비된다. 사실이 부족하거나 충돌하면 추측하지 않고 오류로 드러낸다 |

## 흐름

```
[commerce-api]
  PG 결과 적용 (callback / reconcile)
    TX 밖: 기존 PG 거래 조회·검증
    DB TX ─────────────────────────────────────────────
      주문 CONFIRMED + 예약 소비 + PG 성공 사실 + confirmedAt
      Outbox PENDING insert (eventId, key, payload)
    ── commit ────────────  ① 여기서 멈춰도 Outbox가 남아 있다
                            (rollback이면 주문·예약·Outbox 모두 직전 상태)

  Relay (단일 루프)
    조회 TX: 재시도 시각이 된 PENDING 묶음 (오래된 순)
    TX 밖:   KafkaTemplate.send(key, payload) → future 성공 확인
    ── broker 확인 ───────  ② 여기서 멈추면 PENDING 그대로 → 재발행
    상태 TX: PUBLISHED + publishedAt   (실패·timeout이면 PENDING + 다음 시각·오류)

[Broker]  order-events

[commerce-streamer]  (W6 그대로)
  파싱·검증 → 완료 기록 + 품목 활동 한 TX → ack
  같은 eventId가 다른 offset으로 다시 와도 no-op
```

## 상태의 의미

| 상태 | 뜻 | 뜻하지 않는 것 |
|---|---|---|
| PENDING | 아직 broker 성공을 확인·저장하지 못했다 | 수신 0회 (응답을 잃었을 수 있다) |
| PUBLISHED | relay가 broker 성공을 확인하고 저장했다 | 소비 완료 |
| 소비 완료 | streamer의 완료 기록과 품목 활동이 있다 | 모든 후속 기능 완료 |

구매 활동이 없으면 위치부터 찾는다. Outbox가 PENDING이면 relay를, PUBLISHED인데 활동이 없으면 consumer의 진행·오류를 본다. 재발행은 같은 eventId가 **다른 offset**의 별도 record로 남는 것이고, W6의 같은 offset 재전달과 다르다.

## 알려진 한계

| 한계 | 설명 |
|---|---|
| 단일 relay | 한 번에 한 루프만 돌고 다른 인스턴스에서 켜지 않는 실행 설정으로 지킨다. 분산 잠금이 아니다. 여러 relay는 claim·소유권·만료·순서를 추가로 풀어야 한다 |
| 중복 발행 | broker 성공 뒤 상태 저장 전 중단, 응답 timeout이면 같은 사건이 다시 간다. 소비자 멱등성이 막는다 |
| 자동 복구 못 하는 것 | broker 장기 장애, Outbox 저장소 유실, 읽을 수 없는 payload를 방치한 consumer |
| 보존 | 미발행·격리 행은 자동 삭제하지 않는다. 오래된 이벤트를 다시 보낼 수 있으므로 Outbox·Kafka retention·소비 dedup 기록의 보존 기간은 함께 정한다 |
| 순서 | 주문당 OrderConfirmed 한 건이라 문제없다. 같은 주문의 이벤트가 늘면 발행 순서·버전·소비 정책을 따로 설계한다 |
| 관찰 | 새 모니터링 서버 없이 SQL과 구조화 로그(eventId·orderId)로 PENDING 수·가장 오래 기다린 시각을 본다 |
