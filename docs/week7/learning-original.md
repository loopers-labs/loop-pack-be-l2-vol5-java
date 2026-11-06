> **재시도할 수 있으려면, 무엇을 다시 해야 하는지가 남아 있어야 합니다.**
> 

<aside>
🎯

**Summary**

</aside>

6주차에는 PG 확정 주문을 Kafka로 보내 상품 구매 활동을 저장했습니다. 소비자가 같은 이벤트를 두 번 받아도 활동이 늘지 않지만, **주문 commit 뒤 발행 전에 멈추면 이벤트 자체가 없습니다.** 이번에는 그 빈틈을 Transactional Outbox로 개선합니다.

- **원자성**: 업무 변경과 전달할 사실을 같은 DB에 함께 남깁니다.
- **Relay**: 저장된 사실을 찾아 broker에 전달합니다.
- **재처리**: 발행 응답을 잃거나 상태 저장 전에 멈춰도 같은 사건을 다시 보냅니다.
- **수렴**: 중복 발행·전달을 기존 소비자의 한 업무 결과로 연결합니다.
- **운영 판단**: 미발행·발행 확인·소비 완료를 구분하고 실패를 다시 처리합니다.

<aside>
📌

**Keywords**

</aside>

- **기록** — transactional outbox, immutable payload, local atomicity
- **전달** — polling relay, broker acknowledgment, at-least-once
- **회복** — retry, backoff, replay, quarantine, backlog
- **경계** — eventual consistency, consumer idempotency, CDC, ordering

---

<aside>
🧠

**Learning**

</aside>

## 🕳 중복을 막았는데 구매 활동은 왜 빠졌을까

consumer의 중복 제거는 **도착한 record**를 안전하게 처리합니다. producer가 한 번도 보내지 못한 이벤트를 찾아내지는 못합니다.

| 발행 위치 | 중단·실패 | 남는 문제 |
| --- | --- | --- |
| DB commit 전에 send | broker 기록 뒤 주문 확정 rollback | 실제 확정되지 않은 주문의 구매 활동 |
| DB commit 뒤 send | commit 뒤 send 전 중단 | 확정 주문은 있는데 구매 활동 없음 |
| 메모리 queue에 먼저 보관 | process 종료 | 재시작 뒤 전달 대상 복원 불가 |

앞뒤 순서를 바꾸는 것으로 DB와 Kafka를 하나의 저장소처럼 만들 수 없습니다. 재시도를 추가하려 해도 메모리에만 있던 이벤트를 잃었다면 **어떤 사실이 빠졌는지**부터 찾아야 합니다.

이 문제가 중요하지 않은 경우도 있습니다. 잃어도 되는 임시 통계나 다시 계산 가능한 신호라면 단순한 방식이 적합할 수 있습니다. 하지만 실제 확정 구매를 랭킹 입력으로 보존하려면 빠진 전달을 찾는 책임이 필요합니다.

## 📮 Transactional Outbox

> **Transactional Outbox**는 업무 변경과 외부로 전달할 메시지를 같은 로컬 DB 트랜잭션에 기록하고, 별도의 전달 단계에서 메시지를 보내는 패턴입니다.
> 

```
하나의 DB TX
  ├─ 주문 CONFIRMED · 예약 소비 · PG 성공 사실
  └─ OrderConfirmed Outbox PENDING
                 ↓ commit 뒤
           relay → Kafka → 구매 활동 consumer
```

같은 트랜잭션이라는 말은 이름이 비슷한 두 메서드에 `@Transactional`을 붙였다는 뜻이 아닙니다. 같은 데이터 소스·트랜잭션 관리 범위에서 함께 commit/rollback해야 합니다. Outbox를 `REQUIRES_NEW`로 따로 commit하거나 after-commit listener에서 뒤늦게 insert하면 원래의 빈틈이 다시 생깁니다.

### 🧱 이미 존재하던 주문의 rollback

이번 주문은 먼저 DRAFT로 생성되고, PG 시도를 거쳐 확정됩니다. Outbox 추가 중 오류가 났다고 원래 주문 행이 없어지는 것이 아닙니다.

| 확정 결과 적용 TX | 주문·예약 | Outbox |
| --- | --- | --- |
| 성공 | CONFIRMED·예약 소비 | 같은 확정 이벤트 PENDING |
| 실패·rollback | TX 직전의 미완료 주문·예약 상태 유지 | 새 이벤트 없음 |

PG에서는 이미 SUCCESS일 수 있습니다. 로컬 rollback이 외부 결제를 취소하지 않으므로 5주차의 같은 거래 조회·결과 적용 경로로 다시 시도합니다. 새 PG 결제를 만들지 않습니다.

## 🧾 무엇을 저장할 것인가

Outbox는 “나중에 무언가 보내기”라는 모호한 표지가 아닙니다. 재시작한 relay가 같은 메시지를 만들 수 있을 만큼 정확한 사실이 필요합니다.

| 필드 역할 | 예시 | 이유 |
| --- | --- | --- |
| 사건 식별 | eventId | 재발행해도 같은 사건임을 유지 |
| 업무 식별·key | orderId, messageKey | 기존 주문·partition 연결 |
| 해석 계약 | eventType, schemaVersion | consumer의 payload 해석 |
| 내용 | 확정 당시 items·confirmedAt | 현재 상품을 다시 읽어 과거 사실을 바꾸지 않음 |
| 전달 상태 | PENDING / PUBLISHED | 아직 확인하지 못한 전달을 찾음 |
| 시도·대기 | attemptCount, nextAttemptAt | 실패 반복과 재시도 시점 제어 |
| 관찰 | createdAt, lastError, publishedAt | 오래된 미발행·실패 위치 확인 |

`attemptCount`가 “실제 broker 수신 수”는 아닙니다. 시도를 시작할 때 올리면 전송 전에 중단될 수도 있습니다. 반대로 broker 응답을 잃으면 기록한 성공 횟수보다 수신 수가 많을 수도 있습니다.

**Immutable payload**는 같은 사건을 재발행할 때 내용이 달라지지 않는다는 뜻입니다. relay에서 현재 상품 가격·수량·삭제 상태를 조회해 새 JSON을 만들면 재처리 결과가 달라집니다. 고정된 snapshot과 스키마 버전을 보존합니다.

여기에서 Outbox는 전체 Entity 변경 이력을 저장하는 **event sourcing**이 아닙니다. 주문의 원본은 여전히 주문·결제 DB이며, Outbox는 전달할 사건을 보존합니다.

## 🚚 Relay는 어디까지 완료했는가

> **Relay**는 아직 전달하지 못한 Outbox를 찾아 broker에 보내고 전송 결과를 기록하는 역할입니다.
> 

이번에는 한 프로세스의 단일 실행 흐름이 relay를 맡습니다. DB 조회·상태 변경은 짧은 TX로 수행하고 외부 send 응답을 기다리는 동안 주문 확정 TX를 붙잡지 않습니다.

```
재시도 시각이 된 PENDING 선택
  → 저장된 key·payload로 send
  → broker 확인 성공
  → 별도 DB TX에서 PUBLISHED 저장
```

| 관찰 | relay의 판단 |
| --- | --- |
| broker 확인 성공 | PUBLISHED로 기록할 수 있음 |
| 명시적 전송 실패 | 같은 사건을 재시도 대상으로 유지 |
| 응답 timeout | 수신 여부가 불확실하므로 같은 사건 유지 |
| broker 성공 뒤 DB 상태 저장 실패 | 다시 보낼 가능성을 받아들임 |

`PUBLISHED`는 **relay가 broker 성공을 확인하고 저장했다**는 뜻입니다. consumer가 구매 활동을 저장했다거나 모든 후속 기능이 끝났다는 뜻이 아닙니다.

Kafka send의 로컬 대기 timeout 뒤에도 하위 producer가 아직 전송 중일 수 있습니다. 늦은 callback이 새 시도의 상태를 덮어쓰지 않게 상태 변경 책임을 한 실행 경계로 모읍니다. 타임아웃마다 새 eventId를 만들면 같은 사건의 중복 처리가 깨집니다.

## 🔁 두 번 발행되는 것이 정상인 구간

```
1. 주문 확정 + Outbox E commit
2. relay가 E를 발행, broker 확인
3. PUBLISHED 저장 전에 relay 중단
4. 재시작한 relay가 아직 PENDING인 E를 다시 발행
5. consumer가 E를 두 번 읽어도 구매 활동은 한 번
```

DB 상태를 send보다 먼저 PUBLISHED로 바꾸면 중단 시 미발행 사건을 완료로 숨깁니다. 뒤에 바꾸면 중복 발행 가능성이 남습니다. Outbox는 이 중 **다시 찾을 수 있는 쪽**을 선택하고 소비자가 중복 업무 효과를 막도록 합니다.

### 📍 재발행과 재전달을 구분하기

| 장면 | Kafka record | 업무 eventId |
| --- | --- | --- |
| W6 consumer offset 미커밋 재전달 | 같은 topic·partition·offset을 다시 읽음 | 같음 |
| W7 relay 재발행 | 별도의 record로 기록돼 offset이 다를 수 있음 | 같음 |

producer의 멱등 전송 설정은 애플리케이션이 의도적으로 두 번 send한 동일 업무까지 일반적으로 하나로 합치지 않습니다. 그래서 W6의 **사건 식별·업무 자연 키·원자적 구매 활동 저장**을 계속 사용합니다.

한 이벤트에 두 상품이 있다면 기대 결과는 구매 활동 1행이 아니라 **같은 두 행이 한 번씩** 남는 것입니다. 전달 수와 이벤트 수, 품목 행 수를 하나의 “effect=1”로 뭉개지 않습니다.

## ⏳ 최종적 일관성에 필요한 조건

> **최종적 일관성(eventual consistency)**은 시스템 사이에 일시적 차이를 허용하되, 정해진 전달·처리 조건이 충족되면 의도한 같은 결과로 수렴하는 성질입니다.
> 

“언젠가 맞는다”는 약속만으로는 부족합니다.

| 조건 | 이번 시스템에서 확인할 것 |
| --- | --- |
| 기준 사실이 남음 | 확정 주문과 Outbox |
| 전송이 다시 진행됨 | 실행 중인 relay·접근 가능한 broker·재시도 |
| 소비가 다시 진행됨 | 동작하는 consumer·충분한 보존 기간 |
| 중복을 감당함 | 같은 이벤트의 구매 효과 한 번 |
| 영구 오류를 찾음 | 오래된 PENDING·격리된 오류·처리 책임 |

broker가 계속 꺼져 있거나 payload를 읽을 수 없는 consumer를 방치하면 수렴하지 않습니다. Outbox 저장소를 잃거나 미발행 행을 정리해 버리는 상황까지 자동 복구하지도 않습니다.

## 🛠 재시도와 재처리의 책임

**재시도(retry)**는 실패한 전달을 다시 수행하는 것이고, **재처리(replay)**는 저장된 사건을 선택해 전달·소비 흐름을 다시 실행하는 것입니다. 둘 다 같은 사건의 identity와 내용이 유지돼야 합니다.

| 실패 유형 | 가능한 대응 | 피할 행동 |
| --- | --- | --- |
| broker 일시 장애 | 간격을 둔 제한된 시도, 이후 재개 | 빠른 무한 루프 |
| DB 상태 갱신 오류 | PENDING 유지·같은 사건 재발행 | 새 eventId로 회피 |
| 지원하지 않는 schema | 원인 표시·소비 중단/격리, 호환 코드 복구 | 성공 처리하고 offset만 넘김 |
| 잘못된 payload | 근거와 대상 보존, 별도 판단 | 의미를 바꾸고 같은 사건으로 위장 |

영구 오류를 `BLOCKED` 등으로 분리하면 정상 대상이 계속 진행할 수 있지만, 그 행은 **완료가 아니라 사람이 다시 판단해야 할 미해결 작업**입니다. 무한 재시도를 피했다는 이유로 업무 누락이 해결됐다고 말하지 않습니다.

구매 활동이 없을 때는 먼저 사건의 위치를 찾습니다. Outbox가 PENDING이면 relay를 보고, PUBLISHED인데 활동이 없으면 consumer의 진행·오류를 봅니다. 이미 활동이 있으면 결과를 지우고 처음부터 시작할 필요가 없습니다.

## 📊 관찰과 보존

| 관찰값 | 알려 주는 것 | 단독으로 증명하지 못하는 것 |
| --- | --- | --- |
| PENDING 수·최고 대기 시간 | 발행 backlog·장기 미발행 | 소비 완료 |
| 전송 오류·시도 수 | relay 실패 경향 | broker 실제 중복 기록 수 |
| consumer lag·실패 | 소비 지연·오류 | DB 업무 정합성 |
| 주문별 구매 활동 | 실제 후속 업무 결과 | 모든 주문의 누락 없음 |

SQL 조회와 구조화 로그에 **eventId·orderId를 남겨 원본부터 결과까지 추적**합니다.

PUBLISHED 행을 즉시 삭제하면 전달 이력과 재처리 근거를 잃습니다. Outbox 보존 기간, Kafka retention, 소비 중복 방지 기록의 보존 기간은 함께 정해야 합니다. 오래된 이벤트를 다시 보낼 수 있는데 dedup 기록만 먼저 지우면 중복 효과가 생길 수 있습니다.

## ⚖️ 단일 Relay 다음에는 무엇이 달라지는가

| 선택 | 얻는 것 | 추가 책임 |
| --- | --- | --- |
| 단일 polling relay | 이해·재시작이 단순 | 실행자 하나, 주기·backlog |
| 여러 polling relay | 처리량·가용성 확대 가능 | claim/lock·소유권·만료·중복·순서 |
| CDC 기반 relay | DB 변경 로그를 통한 전달 | connector·offset·스키마·운영 |

CDC는 DB 변경 로그를 읽는 방식입니다. 애플리케이션 polling을 줄일 수 있지만 운영 비용이 사라지는 것은 아닙니다. 이번에는 도입 비교만 하고 단일 polling relay를 구현합니다.

단일 relay에도 여러 scheduler tick이 겹치면 실행자가 둘인 것처럼 동작합니다. 한 번에 한 루프만 돌게 하고, 다중 인스턴스에서 동시에 켜지 않도록 실행 설정을 정합니다. 이를 다중 relay 환경의 분산 잠금 보장이라고 주장하지 않습니다.

이번 이벤트는 주문당 한 번인 OrderConfirmed입니다. 나중에 같은 주문의 변경 이벤트가 여러 개 생기면 key뿐 아니라 발행 순서·버전·소비 적용 정책을 추가로 설계해야 합니다. Outbox가 자동으로 모든 업무 순서를 해결하지는 않습니다.

### 💭 선택을 설명하기

- 왜 주문 확정과 Outbox insert는 같은 TX여야 할까요?
- 왜 발행 성공 뒤에도 같은 사건을 다시 보낼 수 있을까요?
- “발행됨”과 “구매 활동 반영됨”을 어떤 증거로 구분할까요?
- 기존 W6 누락 사건을 복구할 때 어떤 identity를 유지해야 할까요?
- Outbox를 쓰는 비용이 유실 비용보다 커지는 경우는 무엇일까요?

---

<aside>
📚

**References**

</aside>

- Transactional Outbox 패턴
- Spring · Outbox 전략과 트랜잭션 동기화의 차이
- Debezium · Outbox Event Router
- Spring 6.2 · Transaction-bound Events