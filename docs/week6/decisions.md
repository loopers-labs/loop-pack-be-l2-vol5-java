# 6주차 결정 기록

> 설계 대화에서 합의한 결정과 근거, 버린 대안을 남긴다. 한 장 요약은 [`design.md`](design.md).

## 발행 경계

**불변식 (합의)**: 검증된 PG SUCCESS를 처음 내부 CONFIRMED로 반영해 DB commit이 성공한 경우에만 OrderConfirmed를 발행한다.

### D-1. commit 먼저, send 나중

- **결정**: DB commit이 끝난 뒤에 send한다.
- **두 실패 방향**: send를 먼저 하면 롤백돼도 이벤트가 남는다(거짓 이벤트). commit을 먼저 하면 commit과 send 사이에서 끊길 수 있다(누락 이벤트).
- **근거**: 거짓 이벤트는 consumer가 이미 그걸 근거로 행동했으므로 되돌릴 수 없다. consumer는 commerce-api의 주문을 확인하지 않아 오류도 나지 않고 조용히 쌓인다. 누락 이벤트는 주문 DB(CONFIRMED)라는 원천이 남아 있어 찾아낼 수 있고, eventId가 `order-confirmed:v1:{orderId}`로 고정이라 다시 발행해도 consumer가 중복으로 걸러 준다.
- **누락이 생기는 자리**: 서버 사망(배포·OOM·`kill -9`), send 재시도 소진 후 실패, producer 버퍼에 있다가 앱 종료, payload 생성 중 예외.

### D-2. 발행은 `apply` 트랜잭션의 after-commit에 등록한다

- **비교한 대안**: `apply`를 호출한 바깥(orchestration)에서 return 뒤에 send.
- **확인한 사실**: `apply`를 직접 부르는 곳은 `PaymentReconciler.reconcile` 한 곳이고, reconcile을 부르는 곳은 네 곳이다(confirm 직후 재조회, 고객 reconcile, 관리자 reconcile, 회복 작업). reconcile은 `ApplyResult`를 버리고 `Outcome.APPLIED`만 돌려주므로, 바깥에서는 "처음 확정"인지 모른다. 지금 네 호출자에는 트랜잭션이 없다.
- **위험 비교**:
  - orchestration은 새 입구에서 발행을 빠뜨릴 수 있다. 더 나쁜 건 따로 있다. 호출자에 `@Transactional`이 붙으면 `apply`가 바깥 트랜잭션에 합류해 return이 commit을 뜻하지 않게 되고, 경고 없이 거짓 이벤트 쪽으로 넘어간다.
  - after-commit은 흐름이 덜 보이고, afterCommit 예외가 commit 뒤 호출자에게 전파되며, 트랜잭션이 없으면 등록되지 않는다. 그래도 결과는 모두 누락이나 잘못된 응답 쪽이다. commit 성공이 실행 조건이라 거짓 이벤트를 만드는 경로가 없다.
- **근거**: D-1의 기준(거짓 이벤트가 더 나쁘다)과 정면으로 부딪히는 건 orchestration의 "return = commit" 가정뿐이다. 모든 입구가 `apply`를 지나므로 빠뜨림도 없다. 덜 보인다는 대가는 테스트와 주석으로 드러낸다.
- **선례**: 3주차 `ProductCacheInvalidator`의 afterCommit 등록.

### D-3. 고객 응답은 send와 떼어 놓는다

- **결정**: afterCommit에서는 send를 별도 스레드에 넘기기만 한다. 결과(partition·offset 또는 예외)는 `CompletableFuture` 콜백에서 관찰한다. 고객은 send 결과와 상관없이 200을 받는다.
- **근거**:
  - 기다려도 고객 응답은 200 그대로이고, 정합도 바뀌지 않는다. 시간만 쓴다(`delivery.timeout.ms` 기본 120000).
  - 관찰은 기다리지 않아도 콜백으로 된다.
  - broker가 내려가면 `send()` 호출 자체가 metadata를 기다리며 호출 스레드를 붙잡는다(`max.block.ms` 기본 60000). afterCommit은 고객 요청 스레드에서 돈다.
  - afterCommit 예외가 그대로 전파되면 확정된 주문에 500이 나간다. 200은 저절로 되는 게 아니라 지켜야 한다.
- **비교한 대안**: broker를 늘리는 건 "얼마나 자주 죽느냐"를 줄일 뿐, "죽었을 때 고객이 무엇을 겪느냐"는 바꾸지 못한다. 그리고 그건 인프라의 결정이다.
- **실패를 아는 곳**: send를 호출한 producer 쪽뿐이다. consumer는 "안 온 것"을 알 수 없다. 실패 로그에는 orderId, eventId, 예외를 남긴다. orderId만 있으면 주문 DB에서 payload를 다시 만들 수 있다.

### D-4. 누락 구간은 이번 주에 막지 않고 드러낸다

- **결정**: 정상 종료(SIGTERM)에서는 producer close의 flush와 executor의 "남은 작업 끝내고 종료"로 누락을 좁힌다. 갑작스러운 종료(`kill -9`, OOM)에서 생기는 누락은 인정한다.
- **근거**: 실패 로그로 잡히는 건 "알게 된 실패"뿐이다. 서버 사망과 버퍼 유실은 로그가 안 남는다. 빠짐없이 찾으려면 CONFIRMED 주문과 발행 기록을 비교해야 하고, 그걸 자동화한 게 7주차 Outbox다. 이번 주 과제는 직접 발행으로 정해져 있다.
- **실험**: 과제의 누락 구간 재현(hook으로 미발행 1건 + 정상 1건)으로 보여 준다.

### D-5. payload는 `apply` 트랜잭션 안에서, 처음 확정 직후 불변 객체로 만든다

- **결정**: `attempt.apply(...)`가 `SUCCEEDED`를 돌려주고 `order.completePayment(...)`를 마친 직후 payload를 만든다. afterCommit과 별도 스레드에는 그 객체만 넘기고 entity는 넘기지 않는다.
- **근거**:
  - 같은 트랜잭션이라 payload의 값이 곧 commit될 값이다. commit이 실패하면 afterCommit이 돌지 않아 payload도 버려진다.
  - 주문 행을 `FOR UPDATE`로 잡고 있고, CONFIRMED 뒤의 값은 다시 계산하지 않는다(1주차 원칙). 만든 뒤 commit 전까지 바뀔 수 없다.
  - items 정규화(같은 productId 병합, 수량 양수)는 `Order` 생성 시점에 이미 domain이 보장한다(`Order.java:64-70`).
  - 지금 `Order.items`는 EAGER라 다른 스레드에서 읽어도 터지지 않는다. 하지만 그러면 발행의 정확성이 다른 파일의 fetch 설정 한 줄에 달린다. entity는 바뀔 수 있는 객체이고 여러 스레드가 같이 쓰라고 만든 게 아니다.

### D-6. 확정 시각은 정해지는 순간에 저장될 모양으로 맞춘다

- **결정**: `Order.completePayment`와 `Order.confirm`에서 `confirmedAt`을 UTC, 마이크로초 단위로 잘라 정한다.
- **근거**: 확정은 한 번 일어나고, 확정 시각도 하나여야 한다. 그래서 그 시각이 정해지는 자리에서 DB가 담을 모양으로 맞춘다. 그래야 그 값이 어디로 흘러가도(API 응답, 이벤트, DB 재조회, 재발행) 글자 그대로 같다.
- **확인한 사실**: Java 메모리 값은 `2026-10-28T14:02:27.068687700+09:00[Asia/Seoul]`(소수 7자리, 서울)이다. 컬럼은 `datetime(6)`이고, `jpa.yml`의 `NORMALIZE_UTC` 설정으로 UTC로 저장된다.
- **막는 사고**: 처음 발행한 값(메모리)과 D-4의 재발행 값(DB)이 달라 consumer가 같은 eventId를 conflict로 판정한다. 그러면 복구 수단이 경보를 만든다.
- **비교한 대안**: payload에서 자르면 같은 주문의 확정 시각이 고객(API 응답)과 consumer(이벤트)에게 다르게 보인다. `confirmedAt`을 내보내는 곳이 늘 때마다 "자르기"를 기억해야 한다.
- **`confirm()`도 바꾸는 이유**: 근거가 "이벤트가 나가는 경로"가 아니라 "확정 시각이 정해지는 자리"이기 때문이다.
- **덤**: 지금도 confirm 응답(메모리 값)과 이후 GET(DB 값)이 다를 수 있다. 이 결정이 그 틈도 메운다. 구현 때 테스트로 확인한다.

## consumer 쪽

### D-7. 처음 처리한 사실을 정규 형식 문자열로 eventId와 함께 남긴다

- **"같은 사실"의 범위**: `orderId`, `confirmedAt`, items. `eventType`은 D-19에서 OrderConfirmed인지 먼저 검사하므로 넣지 않는다. `schemaVersion`은 payload를 읽는 규칙이지 사실이 아니라서 넣지 않는다. eventId는 최초 확정에 고정되어 형식이 바뀌어도 그대로이고, eventId의 `v1`은 ID를 만드는 규칙의 판이지 payload 형식의 판이 아니다(D-18). consumer가 처리한 시각과 partition·offset은 넣지 않는다. 재전달될 때마다 달라서, 넣으면 정상 재전달이 전부 conflict가 된다.
- **결정**: 받은 JSON을 그대로 저장하지 않고, 읽은 값으로 정해진 규칙(items는 productId 순 정렬, 필드 순서 고정)에 따라 문자열을 다시 만들어 남긴다. 같은 eventId가 다시 오면 같은 규칙으로 만든 문자열과 비교해, 같으면 no-op, 다르면 conflict로 기록한다. conflict 기록에는 처음 사실과 새 사실을 모두 남긴다.
- **비교한 대안**: 필드를 컬럼으로 남기면 items가 목록이라 테이블이 하나 더 필요하다. 해시로 남기면 "다르다"는 것만 알 수 있고 무엇이 다른지는 원본을 찾아야 한다.
- **근거**: conflict 알림을 받은 운영자는 어느 주문이 무엇이 다른지를 바로 봐야 한다. 정규 형식이면 producer의 공백이나 필드 순서와 상관없이 같은 사실은 같은 문자열이 된다. 5주차 `PaymentConflict`가 우리 기록과 PG가 말한 것을 같이 남긴 것과 같은 모양이다.

### D-8. 실패를 "다시 하면 결과가 바뀌는가"로 나눈다

| 실패 | 다시 하면 | 처리 |
|---|---|---|
| DB 장애, 락 타임아웃 | 바뀐다 | ack하지 않고 예외를 던진다. container가 멈추고, DB 복구 뒤 재시작하면 같은 기록부터 다시 처리한다. ack하면 처리 안 한 걸 처리했다고 하는 셈이라 유실이다 |
| conflict | 안 바뀐다 | 기록하고 ack한다. 멈추면 재시작해도 같은 자리에서 영원히 멈추고(poison pill), 같은 partition의 다른 주문까지 막힌다 |
| 계약을 어긴 기록(읽을 수 없거나, 읽혀도 약속을 어김. D-19) | 안 바뀐다 | 주소(topic, partition, offset)를 기준으로 key와 원본 value를 그대로 남기고 ack한다. 주소에 unique를 걸어 재전달돼도 한 번만 기록된다. key(orderId)는 value와 따로 직렬화돼 읽힐 가능성이 크다 |

- **주의**: `MANUAL_IMMEDIATE`에서 ack는 우리 코드가 부른다. catch에서 ack를 부르면 Kafka는 그냥 믿는다. 어느 실패에서 ack를 부를지가 곧 설계다.
- **멈춤은 group 전체로 번진다**: `CommonContainerStoppingErrorHandler`는 자식이 아니라 부모 container를 멈춘다(spring-kafka 3.3.4 `thisOrParentContainer`). 앱이 여러 대면, 멈춘 앱이 group에서 빠지고 rebalance로 그 partition을 받은 다른 앱이 같은 offset에서 또 멈춘다. 결국 group 전체가 선다. 이 분류가 그 연쇄를 안전하게 만든다.
  - 다시 해도 안 바뀌는 실패(conflict, 계약 깨짐)는 기록하고 ack하므로 멈춤 handler까지 가지 않는다. 분류가 없으면 깨진 기록 하나가 모든 consumer를 차례로 멈춰 세운다.
  - 멈춤 handler에 닿는 건 DB 장애 같은 공용 자원 실패뿐이다. 이때는 어느 consumer도 처리할 수 없으니 전체가 멈추는 게 실제 상황과 맞다. 멀쩡한 partition을 계속 읽으려 해도 같은 DB에서 실패한다.
- **계약 깨짐의 책임**: 원인은 producer(우리 배포)인데 알아차리는 건 consumer뿐이다. D-3(send 실패는 producer만 안다)과 거울 관계다. producer는 직렬화 결과 모양을 고정한 계약 테스트로 막고, consumer는 읽지 못한 기록 수를 로그와 지표로 드러낸다. 깨진 사실 자체는 주문 DB에서 다시 발행해 복구한다(D-4).

### D-9. conflict와 읽을 수 없는 기록은 DB 테이블에 남긴다. DLT는 쓰지 않는다

- **비교한 대안**: DLT. 고친 뒤 다시 흘려보내기는 쉽지만, 보려면 Kafka 도구가 필요하고 보존 기간(기본 7일)이 지나면 사라진다.
- **근거**: DLT로 옮기는 것도 Kafka로의 send다. D-1의 "먼저 commit할까, 나중에 할까"를 consumer 안에서 다시 풀어야 한다. DB 테이블은 처리 기록과 같은 트랜잭션에 들어가 그 경계가 생기지 않는다. 과제가 정한 `CommonContainerStoppingErrorHandler`도 DLT로 보내 주지 않는다(DLT는 보통 `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`).

### D-10. unique 경합이 나면 롤백하고 새 트랜잭션에서 비교한다

- **경합이 생기는 때**: concurrency 1에 key가 orderId여도, 죽은 줄 알았는데 살아 있는 consumer(좀비)가 있으면 생긴다. 처리가 느려 `max.poll.interval.ms`를 넘기면 coordinator가 rebalance하고, 새 주인이 committed offset부터 같은 기록을 처리한다. 배포 중 옛 인스턴스와 새 인스턴스가 겹칠 때도 같다.
- **MySQL에서의 모습**: 두 번째 insert는 첫 번째 트랜잭션이 끝날 때까지 기다린다. 첫 번째가 commit하면 `Duplicate entry`를 받고, 롤백하면 insert가 성공한다.
- **같은 트랜잭션에서 이어서 확인하지 않는 이유**:
  - 예외가 경계를 지나면 Spring이 트랜잭션을 rollback-only로 표시한다. Hibernate도 예외가 난 Session은 다시 쓰지 말라고 한다. 게다가 그 트랜잭션의 구매 활동 반영은 어차피 버려야 한다.
  - Duplicate 판정은 최신 commit 값 기준이다. 같은 트랜잭션의 일반 SELECT는 첫 읽기 때의 스냅샷을 본다. "있다고 해서 에러가 났는데 찾아보면 없다"는 모순이 생길 수 있다(3주차 락 실습 2와 같은 원리).
- **흐름 (합의)**:
  - A가 먼저 commit하면, B는 Duplicate를 받고 롤백한 뒤 새 트랜잭션에서 비교한다. 같으면 no-op, 다르면 conflict로 기록한다. 그리고 ack한다. 구매 활동은 1번 반영된다.
  - A가 롤백하면, B의 insert가 성공하고 B가 반영한 뒤 ack한다. 구매 활동은 1번 반영된다.

### D-18. 처리 기록은 eventId와 orderId를 각각 unique로 둔다. 같은 주문의 다른 eventId는 내용이 같아도 conflict다

- **결정**:
  - `processed_order_confirmation`에 `event_id` unique와 `order_id` unique를 둔다. 구매 활동의 `(order_id, product_id)` unique는 그대로 둔다(업무의 자연 키).
  - Duplicate가 나면(D-10) 롤백한 뒤 새 트랜잭션에서 처리 기록을 읽어 판단한다.

    | 저장된 처리 기록 | 판단 |
    |---|---|
    | 같은 eventId, 같은 orderId, 같은 사실(D-7) | no-op, ack |
    | 같은 eventId인데 orderId나 사실이 다름 | conflict 기록, ack |
    | 같은 orderId인데 eventId가 다름 | **내용이 같아도** conflict 기록, ack |

  - 구매 활동 unique만 걸리는 경우(처리 기록 없이 활동만 있는 불일치)도 conflict로 기록한다. 다시 해도 안 풀린다.
- **같은 orderId·다른 eventId를 conflict로 보는 근거**: 과제는 eventId를 최초 확정 사실에 고정하고 재발행마다 새 ID를 만들지 못하게 했다. 그 약속 아래에서 같은 주문의 eventId가 달라졌다면 producer가 식별 계약을 어긴 것이다. 내용이 같다고 넘기면 그 위반이 묻힌다. 과제 문장도 "같은 orderId의 다른 eventId·사실은 충돌로 드러낸다"이다.
- **바로잡은 것**: 처음에는 처리 기록을 eventId로만 지키고, 같은 주문이 v2 eventId로 오면 구매 활동 unique에서 걸린 뒤 `quantity`·`confirmedAt`이 같으면 no-op으로 보려 했다. 그 근거로 "orderId만 키로 쓰면 이후의 주문 취소 같은 다른 종류 사실을 재전달로 오인한다"는 반례를 들었다. 하지만 이 테이블은 이름대로 **주문 확정 전용**이다. 주문 하나에 확정은 한 번이라 orderId unique가 자연스럽고, 다른 종류 사실은 이 테이블에 오지 않는다. 반례가 테이블의 범위를 놓쳤다. 형식을 바꿔도 eventId는 그대로여야 하므로(`v1`은 ID 규칙의 판, D-7) "v2 eventId로 재발행"이라는 장면 자체가 계약 위반이다.
- **남은 것**: conflict 기록에는 처음 사실과 새 사실을 둘 다 남겨(D-22), 운영자가 무엇이 다른지(또는 eventId만 다른지) 바로 본다. `confirmedAt`이 글자 그대로 비교되는 건 D-6 덕분이다.

### D-19. 이벤트 안의 약속 위반은 insert 전에 검사해 계약 깨짐으로 기록한다

- **생기는 때**: 한 이벤트의 items에 같은 productId가 두 줄 있는 경우(`{7, 2}, {7, 1}`). 주문은 만들 때 같은 상품을 한 줄로 합치고 수량 0 이하를 거절하므로(`Order` 생성), 실제 주문을 옮긴 이벤트라면 생길 수 없다. 두 번 산 게 아니라 producer 코드가 계약을 어긴 것이다.
- **D-18 방식으로 못 잡는 이유**: 같은 트랜잭션에서 첫 insert는 성공하고 두 번째가 Duplicate가 나 전부 롤백된다. 롤백 뒤 저장된 줄을 찾아도 없다. 비교 대상이 없고, 다시 해도 같은 Duplicate가 난다. Duplicate만 보고는 "다른 이벤트와 겹쳤는지, 자기 안에서 겹쳤는지" 구분할 수 없다.
- **분류**: D-8의 계약 깨짐. JSON은 읽히지만 원인이 producer 배포에 있고, 다시 해도 안 풀리고, 고치는 길은 주문 DB에서 재발행(D-4)뿐인 점이 JSON이 깨진 경우와 같다. 주소와 원본을 남기고 ack한다.
- **결정**: consumer는 파싱 뒤 insert 전에 이벤트 자체의 약속을 검사한다.
  - 형식: `eventType`이 OrderConfirmed, `schemaVersion`이 1, 필수 필드가 있음.
  - 식별 관계: record key가 payload의 orderId 문자열과 같음. eventId의 모양은 여기서 검사하지 않는다(아래 "바로잡은 것").
  - items: 비어 있지 않고, productId 중복 없음, 수량 1 이상. producer도 payload를 만들 때 같은 약속을 지키지만, consumer는 그걸 믿고 검사를 생략하지 않는다.
- **근거**: producer의 검사도 코드라 빠지거나 틀릴 수 있고, 그때 알아챌 곳은 consumer뿐이다(D-8 "계약 깨짐의 책임"). 원인이 이벤트 안에만 있으니 DB 없이 판단할 수 있다. insert 전에 걸러 두면 Duplicate는 "다른 이벤트와 겹쳤다"는 한 가지 뜻만 갖게 되어 D-18의 비교가 성립한다.
- **바로잡은 것**: 처음에는 식별 관계에 "eventId가 `order-confirmed:v1:{orderId}`와 같음"도 넣었다. 묶음 2를 구현하다가, 이 검사가 D-18의 "같은 orderId·다른 eventId → conflict"를 가로챈다는 것이 테스트로 드러났다. 같은 주문에 다른 eventId가 오면 insert 전에 계약 깨짐으로 걸려서 D-18의 비교까지 가지 못한다. 과제 문장은 이 장면을 "충돌로 드러낸다"고 한다. 그래서 eventId 검사를 뺐다.
  - 경계: key와 orderId의 불일치는 이벤트 하나만 보고도 모순이므로 계약 깨짐이다. eventId가 다르다는 것은 이미 처리한 기록과 비교해야 뜻이 생기므로 D-18의 conflict다.
- **한계**: 어떤 주문의 **첫 기록**이 규칙과 다른 eventId로 오면 비교할 기록이 없어서 그대로 받아들인다. 이 위반은 같은 주문이 올바른 eventId로 다시 올 때(재발행 등) conflict로 드러나고, 그 전까지는 드러나지 않는다. eventId를 규칙대로 만드는 일은 producer 쪽 계약 테스트(`KafkaOrderConfirmedPublisherContractTest`, 계약 JSON의 eventId)가 지킨다.

### D-22. conflict와 계약 깨짐은 테이블을 나눈다. 둘 다 주소에 unique를 건다

- **결정**:
  - conflict 테이블: 주문 번호, 처음 사실, 새 사실을 `NOT NULL`로 둔다(D-7, D-18).
  - 계약 깨짐 테이블: 주소(topic, partition, offset)와 원본 바이트를 `NOT NULL`로 둔다. key(orderId)는 읽혔을 때만 남긴다(D-8, D-19).
  - 둘 다 주소에 unique를 걸어, 기록 뒤 ack 전에 죽어 재전달돼도 한 번만 기록한다.
- **처음 세운 근거가 무너진 과정**: "중복을 막는 열쇠가 다르니 나눈다"고 생각했지만, 재전달된 기록은 주소가 같으므로 conflict도 주소를 열쇠로 쓸 수 있었다. 열쇠는 나눌 근거가 못 된다.
- **근거**: 두 기록은 반드시 채워지는 칸이 다르다. conflict는 파싱에 성공한 뒤에 생기므로 주문 번호와 두 사실이 항상 있다. 계약 깨짐에서 확실한 건 주소와 원본뿐이다. 한 테이블에 담으면 conflict 쪽 칸까지 전부 비어도 되는 칸이 되어 "새 사실이 빠진 conflict" 같은 잘못된 줄을 DB가 막지 못한다.
- **비교한 대안**: 한 테이블. "주문 100에 무슨 문제가 있었나"를 한 번에 조회할 수 있다. 하지만 key까지 깨진 계약 깨짐 기록은 주문 번호가 비어 `WHERE order_id = 100`에 걸리지 않는다. 한 번에 다 보인다는 느낌이 오히려 거짓이 된다.
- **조회**: 두 테이블을 각각 본다. 계약 깨짐 쪽은 key가 없는 기록이 있을 수 있음을 전제로 본다.
- **운영자가 하는 일**: conflict는 주문 DB에서 진짜 값을 확인해 틀린 쪽을 바로잡는다. 계약 깨짐은 그 시각의 producer 배포에서 버그를 찾아 고치고 주문 DB에서 재발행한다(D-4).

## listener 설정

### D-11. ack는 `MANUAL_IMMEDIATE`, 처리 단위는 record 한 건이다

- **사실**: `MANUAL`은 `ack()`를 표시만 해 두고 poll로 받은 기록을 다 처리한 뒤 모아서 commit한다. `MANUAL_IMMEDIATE`는 ack하는 순간 commit한다. offset 10~14를 받아 10~12를 ack하고 13에서 멈추면, 재시작 후 `MANUAL`은 10부터, `MANUAL_IMMEDIATE`는 13부터 읽는다.
- **근거**: 멱등 consumer라 결과는 둘 다 같다. 하지만 `MANUAL_IMMEDIATE`면 committed offset이 처리 완료 지점과 한 건 단위로 일치해서, 실패와 재시작이 offset 숫자로 그대로 드러난다(과제의 n → n → n+1 실험). 재처리 범위도 실패한 그 한 건으로 줄어든다.
- **대가**: 기록마다 broker에 commit하러 한 번씩 다녀와서 처리량이 준다.
- **열린 질문**: 실무에서 `MANUAL`을 고르는 기준. 멘토링 질문으로 남겼다(`docs/study/mentor-questions-ack-mode.md`).

### D-12. 우리 consumer group은 `auto.offset.reset=earliest`로 덮어쓴다

- **사실**: 공용 `kafka.yml`이 `latest`다. 새 group은 배정받은 순간의 로그 끝부터 읽는다. `modules/*`는 고치지 않고 commerce-streamer 설정에서 덮어쓴다.
- **`latest`의 문제**: 운영에서는 첫 배포 전에 쌓인 이벤트와 배정 전 몇 초의 이벤트를 읽지 않는다. 테스트에서는 send가 배정보다 먼저면 영영 안 읽혀 가끔 실패한다(flaky).
- **`earliest`의 대가**: 운영에서는 남아 있는 이벤트를 처음부터 다시 읽지만, 고정 eventId와 처리 기록(D-7) 덕분에 멱등이라 괜찮다. 다만 "남아 있는 것"은 보존 기간(기본 7일) 안의 것뿐이다. 그보다 오래된 사실을 다시 쌓는 근거는 주문 DB다(D-4). 테스트에서는 같은 topic을 쓰는 이전 테스트의 이벤트까지 읽게 된다 → 테스트 격리를 따로 정해야 한다.

### D-13. 통합 테스트는 주문 ID와 상품 ID를 테스트마다 고유하게 쓰고, 자기 ID로만 검증한다

- **반례 1**: 주문 ID만 다르면 이전 테스트의 이벤트도 새 사실로 처리된다. 구매 활동은 (주문, 상품)마다 한 행이지만, 9주차 랭킹처럼 "상품 1의 구매 활동"을 상품 기준으로 모아 검증하면 같은 상품을 쓴 다른 테스트의 행이 섞인다. (처음에는 상품별 누적 카운터로 잘못 가정하고 설명했다. 과제 3번 표의 "orderId·productId별 하나"로 바로잡았다. 결론은 같다.)
- **반례 2**: `DatabaseCleanUp`은 DB(처리 기록 포함)만 비우고 topic은 못 비운다. `earliest`로 다시 읽은 옛 이벤트가 "처음 보는 eventId"로 다시 반영된다.
- **근거**: 상태를 전부 비우는 대신 입력이 겹치지 않게 하면, 남은 흔적이 있어도 결과가 섞이지 않는다. 검증은 자기가 만든 주문·상품만 조회한다. 테이블 전체를 세지 않는다.

### D-14. JSON 파싱은 deserializer가 아니라 listener 코드에서 하고, value는 바이트 그대로 받는다

- **사실**: `kafka.yml`의 consumer `value-serializer`는 오타라 Spring Boot가 조용히 무시하고, 실제로는 기본값 `StringDeserializer`가 쓰인다. `modules/*`는 고치지 않고 우리 factory에서 `ByteArrayDeserializer`를 직접 지정한다(원래 의도와 같은 값이다).
- **deserializer에서 파싱하지 않는 이유**: 파싱이 실패하면 listener가 호출되지 않고 예외가 error handler로 직행한다. 우리 handler는 멈춤이라 깨진 기록 하나가 poison pill이 된다. `ErrorHandlingDeserializer`를 써도 container가 실패를 error handler로 넘기는 건 같다.
- **listener에서 파싱하면**: 읽을 수 없는 기록을 D-8대로 남기고 ack할 수 있다. 실패 분류(DB 장애, conflict, 읽을 수 없는 기록)가 listener 코드 한 곳에 모인다.
- **바이트로 받는 이유**: `StringDeserializer`는 UTF-8로 해석하면서 잘못된 바이트를 대체 문자(U+FFFD)로 바꾼다. 원본이 되돌릴 수 없게 사라진다. 바이트로 받아야 D-8의 "원본 그대로"가 지켜진다.

### D-15. 우리 record factory는 공용 batch의 fetch 값을 가져오지 않고, 필요한 값을 명시한다

- **사실**: 공용 `BATCH_LISTENER`는 `fetch.min.bytes=1MiB`, `fetch.max.wait.ms=5000`, 최대 3000건이다. 이 값들은 그 factory에만 따로 넣어서, 공통 설정에서 새로 만든 factory에는 따라오지 않는다(Kafka 기본값은 `fetch.min.bytes=1`, `fetch.max.wait.ms=500`).
- **결정**: `fetch.min.bytes=1`과 작은 `fetch.max.wait.ms`, `enable.auto.commit=false`를 우리 factory에 명시하고, 실제 consumer에 들어간 값을 테스트로 확인한다.
- **근거**: 공용 값은 많이 모아 한꺼번에 처리하는 batch용으로, 처리량을 위해 지연을 내준 설정이다. 한 건씩 처리하는 우리 consumer에는 지연만 준다. 운영에서는 이벤트마다 최대 5초, 테스트에서는 매번 5초다. 기본값에 기대지 않고 명시해서, 공용 설정이 바뀌어도 우리 동작이 바뀌지 않게 한다. 공용 yml의 `properties.enable-auto-commit`은 Kafka가 읽는 이름(`enable.auto.commit`)이 아닐 수 있다.

### D-20. 멈춘 consumer는 자동으로 다시 켜지 않는다. 테스트는 registry로 재시작을 흉내 낸다

- **사실**: `CommonContainerStoppingErrorHandler`는 멈추기만 한다. DB가 복구돼도 스스로 다시 켜지 않는다. broker도 켜 주지 않는다. coordinator가 아는 건 "member가 떠났다"뿐이고, consumer를 켜는 건 앱 안의 일이다. 앱 프로세스와 HTTP health check는 멀쩡해서 겉으로는 드러나지 않는다.
- **아무도 안 켜면**: committed offset이 고정되고 lag만 쌓인다. 7일(`log.retention.hours`)이 지나면 이벤트가 지워진다. member 없는 group의 committed offset도 7일(`offsets.retention.minutes`) 뒤 지워져서, 나중에 재시작하면 `auto.offset.reset`을 다시 따른다(`latest`였다면 쌓인 것을 전부 건너뛴다. D-12). 지워진 사실은 주문 DB에서 재발행해 복구한다(D-4).
- **알아채는 법**: lag. 멈추면 committed offset은 그대로인데 log-end offset은 계속 늘어서 lag가 줄지 않는다.
- **결정**: 이번 주에는 사람이 재시작한다(앱 재시작). 통합 테스트는 `KafkaListenerEndpointRegistry`로 그 사람을 흉내 낸다. 장애 주입 → container 멈춤 확인 → 장애 제거 → `start()` → 실패한 offset부터 이어 처리되는지 확인.
- **비교한 대안**: 자동 재시작(스케줄러가 멈춘 container를 `start()`).
  - 별도의 DB 확인(`SELECT 1`)은 필요 없다. 다시 켜면 실패한 기록을 다시 처리하는데, 그게 "이 일을 실제로 할 수 있는가"를 보는 가장 정확한 확인이다. 아직 장애면 또 멈출 뿐이다.
  - 대신 간격을 정해야 한다. 너무 자주 켜면 에러 로그가 쏟아지고, 켜고 멈출 때마다 group을 나갔다 들어와 rebalance가 나서 멀쩡한 다른 앱까지 partition을 다시 나눠 받는다. 너무 드물면 복구 뒤에도 lag가 쌓인다. 보통은 지수 백오프(1s, 2s, 4s… 상한)로 푼다.
- **근거**: 이번 주 실험이 보여 줄 장면은 "멈춘다 → offset이 고정되고 lag가 쌓인다 → 다시 켜면 정확히 그 offset부터 이어 간다"이다. 자동 재시작은 멈춘 구간을 줄이고 간격·rebalance라는 변수를 섞어 그 장면을 흐린다. 과제가 정한 범위도 "모르면 멈추고 사람이 본다"까지다. 자동 재시작은 백오프, 반복 rebalance, 시도 횟수까지 정해야 하는 다른 오류 처리 전략이다.
- **보류**: 멈춘 consumer를 lag 알림으로 알아채고 자동으로 다시 켜는 일. 앱이 여러 대가 되고 lag 지표와 알림이 갖춰졌을 때 검토한다.

## 발행 구조와 실험

### D-16. 발행은 application의 인터페이스와 infrastructure의 Kafka 구현으로 나눈다

- **결정**: application에 D-5의 payload를 받는 `publish` 메서드 하나짜리 인터페이스를 둔다. Kafka로 실제 send하는 구현은 infrastructure에 둔다(5주차 `PaymentGateway`와 같은 모양).
- **근거**: "commit 뒤에 발행을 시작한다"는 트랜잭션 전략이 바뀔 때(7주차 Outbox) 바뀌고, "Kafka로 보낸다"는 전송 수단이 바뀔 때 바뀐다. 이유가 다르니 떼어 둔다. `application`이 `infrastructure`에 의존하지 않는다는 계층 규칙(`ArchitectureTest`)과도 맞는다.
- **실험**: 이음매에서 구현만 갈아 끼우므로 운영 코드에 테스트용 분기가 없다.
  - 전송 직전 중단: Kafka에 닿기 전에 멈추는 가짜 → 주문 CONFIRMED·예약 소비는 남고, broker 기록과 구매 활동은 없다(누락 구간이 실재한다).
  - 전송 실패 주입: 결과가 실패로 돌아오는 구현 → 위와 같고, 고객은 200을 받으며, 실패가 orderId·eventId와 함께 로그로 드러난다.
  - 정상 1건: 진짜 Kafka 구현으로 partition·offset을 관찰한다.

### D-17. 별도 스레드로 넘기는 일은 application(발행 시작) 쪽 책임이다

- **근거**: D-3의 두 근거 중 "고객 응답은 send 결과와 상관없다"는 application의 사정이고, "`send()`가 metadata를 기다리며 스레드를 붙잡는다"는 Kafka 구현의 사정이다. 전송 수단을 바꿔도 그 수단이 느리거나 장애가 날 수 있으니, 고객 응답과 떼어 놓을 필요는 남는다. 그래서 결정적인 근거는 application 쪽이다.
- **함께 따라오는 것**: 정상 종료 때 남은 작업을 끝내는 설정(D-4)도 application 쪽 executor의 일이다. Kafka 구현은 자기 대기 시간에 상한을 두는 일만 맡는다.

### D-21. consumer 쪽 실험은 셋으로 나눈다. 실험마다 증명하려는 것이 다르다

- **A. partition 배정** (Testcontainers Kafka, partition 2개인 격리 topic, 저수준 `KafkaConsumer`로 같은 group)
  - consumer 1개: partition 0과 1을 **둘 다** 맡는다.
  - 2개: rebalance가 일어나 하나씩 나눠 갖는다. 누가 어느 쪽을 갖는지는 assignor가 정하므로 assert하지 않는다.
  - 3개: 적어도 하나는 배정이 없다. group 안에서 partition 하나는 consumer 하나만 맡는다.
  - 매 단계 consumer들이 poll을 계속하며 **실제 assignment가 목표 조건에 도달할 때까지 유한하게 기다린다**. 잠만 자거나 consumer 객체 수만 세지 않는다. 배정표를 테스트 출력으로 남긴다(PR용).
  - 두 partition에 명시적으로 기록을 보내 읽히는지 확인한다. 실험 topic은 업무 topic과 분리해 가짜 구매 활동을 만들지 않는다.
  - key 매핑: producer가 partition을 고르게 두고, 같은 key로 여러 번 보낸 send metadata의 partition이 같은지 확인한다. 다른 key가 다른 partition으로 간다고는 assert하지 않는다.
  - 학습 단계의 CLI(`kafka-console-consumer`, `kafka-consumer-groups --describe`) 관찰은 학습 기록으로 두고, 제출용 재현은 이 테스트다.
- **B. 저수준 재전달** (`KafkaConsumer` 직접, 실제 파싱과 구매 활동 handler를 호출)
  - 과제 표의 순서: 실제 OrderConfirmed 발행 → send metadata로 partition·offset n 확보 → 첫 consumer가 배정된 뒤 n을 commit하고 n에서 읽음 → handler가 DB commit → n+1을 commit하지 않고 bounded close(auto commit false, 종료 commit 없음) → 같은 group의 새 consumer가 seek 없이 n을 다시 받음 → handler no-op → n+1 commit.
  - 관찰값: 전달 2회, 같은 topic·partition·offset, 구매 활동 행 수·수량 불변, committed offset n → n → n+1. position이나 로그로 판정하지 않고 group의 committed offset을 admin으로 읽는다.
  - commit 값은 "다음에 읽을 위치"다. Spring `MANUAL_IMMEDIATE`의 `ack.acknowledge()`도 position이 아니라 그 기록의 `offset() + 1`을 지정해 commit한다(spring-kafka 3.3.4 `buildSingleCommits`). 이 실험이 D-11의 근거를 저수준에서 보여 준다.
  - (과제 밖 추가 장면) 5건(n~n+4)을 받고 n만 처리한 뒤 인자 없이 `commitSync()` → n+5가 commit돼 새 consumer가 n+5부터 받는다(유실). 인자 없는 commit은 처리한 곳이 아니라 poll이 옮겨 놓은 position을 commit한다. 실험 topic에서 구매 활동 없이 한다.
  - (과제 밖 추가 장면 2) 5건을 쌓아 두고 `max.poll.records=2`로 poll 한 번 → 돌려준 기록 [m, m+1], position m+2, 인자 없는 `commitSync()` 뒤 committed m+2. position은 broker에 쌓인 만큼이 아니라 poll이 돌려준 만큼만 움직인다(kafka-clients 3.8.1 `FetchCollector#fetchRecords`). 네트워크로 미리 받아 둔(prefetch) 양 자체는 측정하지 않았다.
- **C. Spring listener 실패와 재시작**: D-20의 흐름을 따른다(장애 주입 → 멈춤 확인 → committed offset 고정 → 장애 제거 → registry `start()` → 같은 offset부터 이어 처리). 장애는 세 방식으로 주입하고, 방식마다 증명하는 것이 다르다.
  - **C-1. 이음매의 가짜**: consumer가 저장을 맡기는 인터페이스 자리에 켜면 예외를 던지는 구현을 넣는다(D-16과 같은 방식). 멈춤과 재개의 **흐름**을 결정적으로 보인다. 던지는 예외를 우리가 고르므로, 실제 DB가 그 예외를 던지는지는 증명하지 못한다.
  - **C-2. 다른 연결의 락**: 별도 커넥션이 consumer가 insert할 자리를 잠근 채 버텨 락 타임아웃을 낸다. 롤백하면 장애가 걷힌다(3주차 락 실습 방식). 진짜 DB 예외가 우리 분류에서 "다시 하면 풀리는 실패"로 가는지를 증명한다. 기본 `innodb_lock_wait_timeout`(50초)을 consumer 연결에서 짧게 줄여야 하고, 무엇을 잠가야 insert가 막히는지 구현 때 확인한다.
  - **C-3. DB 자체를 멈춤**: 공용 MySQL 컨테이너는 JVM 전체가 static으로 공유하고 `modules/*`는 수정 금지라, 이 테스트 전용 컨테이너를 앱 쪽 테스트에서 따로 띄우고 datasource를 덮어쓴다. 연결 자체가 끊긴 장애와 커넥션 풀이 회복하는 과정까지 보인다. 가장 실제에 가깝고 가장 느리다. **`check`에서 빼고 필요할 때만 따로 돌린다.** 매번 지켜 주는 것(연결 끊김에서의 멈춤·재개)은 C-1·C-2가 흐름과 분류로 대부분 덮고, 남는 건 커넥션 풀 회복이다. 그건 datasource·커넥션 풀 설정이나 실패 분류 코드를 바꿀 때 확인하면 된다. 떼는 방법: `@Tag("experiment")`를 붙여 기본 `test`에서 제외하고, `./gradlew :apps:commerce-streamer:experimentTest`로 따로 돌린다. 장애는 컨테이너 stop, 복구는 같은 컨테이너 start다. 다시 켜도 JDBC URL이 같도록 host 포트를 고정한다. pause는 쓰지 않는다. 열린 연결이 끊기지 않고 매달리기 때문이다(MySQL 드라이버 socketTimeout 기본값이 무한). 커넥션 획득 대기(Hikari connection-timeout)는 이 테스트에서만 짧게 둔다.
  - **(과제 밖 추가 장면) error handler 비교**: DB 없이 spring-kafka container를 코드로 띄우고(`MANUAL_IMMEDIATE`), 3건(m, m+1, m+2) 중 m+1에서만 계속 예외를 던진다. error handler만 바꿔 같은 흐름을 두 번 돌린다.
    - `DefaultErrorHandler`(Spring 기본값, `FixedBackOff(0, 9)`): m+1을 10번 시도한 뒤 로그만 남기고 넘어간다. ack한 기록 [m, m+2], committed m+3. **m+1은 처리되지 않은 채 지나간다.** 실패한 기록을 그 자리에서 commit하지는 않지만(`MANUAL_IMMEDIATE`는 `ackCurrent`의 commit 분기를 타지 않는다), 다음 기록의 ack가 그 offset + 1을 commit하면서 건너뛴다.
    - `CommonContainerStoppingErrorHandler`(과제 지정): m+1을 1번 시도하고 멈춘다. ack한 기록 [m], committed m+1, container running=false.
    - 의미: "예외를 잡지 않으면 ack에 닿지 않는다"(INV-606)만으로는 유실을 막지 못한다. 실패한 기록 뒤의 ack가 그 기록을 덮지 않게 하는 건 멈춤 handler다. D-29의 "모르면 멈춘다"가 기본값에서는 "열 번 해 보고 넘어간다"가 된다.
- **근거**: concurrency 1(과제 지정)은 주문별 순서 때문이 아니다. 같은 key는 같은 partition으로 가고 partition은 group 안에서 consumer 하나만 맡으므로, concurrency가 2여도 같은 주문은 한 스레드에서만 처리된다. 1로 두면 offset 기록이 한 스레드에서 한 줄로 나와 실험 관찰이 단순해진다. concurrency를 올려도 `CommonContainerStoppingErrorHandler`는 부모 container를 멈추므로 실패 하나가 앱의 consumer 전체를 세운다(D-8).

## 구현 기반

### D-23. 발행 executor는 스레드 1개·유한 큐이고, 넘치면 기록하고 버린다. producer 대기 상한은 5초다

- **결정**
  - `max.block.ms` = 5000. 우리 전용 producer에만 적용한다.
  - executor: 스레드 1개, 큐 1000.
  - 큐가 차면 orderId·eventId를 "미발행"으로 경고 로그에 남기고 버린다(커스텀 거절 정책).
  - 정상 종료에서는 남은 작업을 끝낼 때까지 기다린다. 대기 시간은 `max.block.ms` 이상으로 둔다(D-4). executor 종료가 producer close보다 먼저 일어나야 한다.
- **근거** (INV-603: 발행의 실패와 지연은 확정 주문과 고객 응답을 바꾸지 않는다)
  - `max.block.ms`: send가 별도 스레드로 옮겨 갔으니, 기본값 60초는 이제 고객이 아니라 발행 스레드가 막히는 시간이다. 정상일 때 metadata 조회는 ms 단위라 5초면 넉넉하다. 장애일 때는 5초 만에 실패가 콜백과 로그로 드러난다. 전송 전체의 상한은 `delivery.timeout.ms`가 따로 맡는다.
  - 스레드 1개: 정상일 때 `send()`는 버퍼에 넣고 바로 돌아온다. 장애일 때는 스레드를 늘려도 모두 같이 막힌다. 주문마다 이벤트가 하나라 순서 문제도 없다.
  - 큐 1000: 회복 장치가 아니라 장애가 길어질 때 메모리를 묶어 두는 상한이다. payload가 작다.
  - 거절 정책:
    - `CallerRunsPolicy`는 고객 요청 스레드(afterCommit)가 `send()`를 직접 실행하게 한다. D-3에서 피하려던 상황이다.
    - `AbortPolicy`는 afterCommit에서 예외를 던져, 확정된 주문에 500이 나간다.
    - `DiscardPolicy`는 흔적을 남기지 않는다.
    - 그래서 기록하고 버린다. 이 누락은 D-4가 인정한 누락과 같은 종류이고, 로그로 드러난다.
- **대가**: 장애가 길어지면 큐가 넘친 만큼 누락이 생긴다. 다시 채우는 일은 7주차 Outbox가 맡는다.

### D-24. 테스트와 검사 기반

- **api의 Kafka 테스트**: 앱 test 쪽에 static 공용 Testcontainers broker를 둔다. 기존 MySQL·Redis fixture와 같은 방식이다.
  - `modules/kafka`의 testFixtures에는 의존성만 있고 소스가 없다. `modules/*`는 수정 금지라 앱 쪽에 둔다.
  - producer·admin 모두에 컨테이너의 실제 주소를 넣는다. `kafka:9092`와 `localhost:19092`를 섞지 않는다.
- **streamer 검사**: api와 같은 Checkstyle 설정(`maxWarnings = 0`)과 계층 ArchUnit을 붙인다. 이번 주에 streamer에 계층이 처음 생기기 때문이다.
- **두 앱에 걸친 정상 1건**:
  - 두 앱이 같은 계약 JSON(`docs/week6/contract/`)을 쓴다. api는 "이 JSON을 만든다", streamer는 "이 JSON을 읽는다"를 테스트한다. 계약이 어긋나면 둘 중 하나가 깨진다.
  - 끝까지 잇는 실행은 로컬 compose로 하고, 결과를 PR에 기록한다.
  - 두 앱을 한 테스트에서 띄우려면 Gradle 모듈 경계를 넘어야 해서 비용이 크다.
- **정리**:
  - `DemoKafkaConsumer`를 지운다. batch listener라 D-8의 단건 처리와 섞이면 혼란만 준다.
  - streamer의 `spring.application.name`을 `commerce-streamer`로 바로잡는다. 지금은 복사 실수로 `commerce-api`라서 메트릭과 로그에서 두 앱이 같은 이름으로 보인다.

### D-25. api는 전용 String producer로 보내고, 시각은 소수 6자리로 고정해 쓴다

- **결정**
  - commerce-api infrastructure에 우리 전용 `KafkaTemplate<String, String>`을 둔다. 공용 `kafkaTemplate`은 그대로 둔다.
  - 이 producer의 설정: value는 `StringSerializer`, `acks=all`, `max.block.ms=5000`(D-23).
  - JSON은 우리 코드가 만든다. `confirmedAt`은 항상 `yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'`(소수 6자리)로 쓴다.
- **근거**
  - 공용 `kafka.yml`의 value serializer는 `JsonSerializer`이고, 그 안의 ObjectMapper는 Spring 쪽 설정과 다르다. 시각이 숫자(timestamp)로 나갈 수 있어 INV-602가 다른 설정 파일에 달린다.
  - `Instant.toString()`은 끝자리 0을 지운다(`.123400` → `.1234`, `.000000` → 생략). 그러면 같은 시각이 글자로는 달라질 수 있다.
- **비교한 대안**: api `application.yml`에서 공용 producer의 value serializer와 `max.block.ms`를 덮어쓰고 공용 `kafkaTemplate`을 쓰는 방법. 코드는 적다. 하지만 버렸다.
  - 이 발행만의 사정(D-23, D-25)이 앱의 모든 producer 규칙으로 번진다.
  - `KafkaTemplate<Object, Object>` 타입인데 실제로는 String만 보낼 수 있다. 잘못 쓰면 컴파일 에러가 아니라 실행 중 `ClassCastException`이 난다.
  - api에 두 번째 Kafka 사용자가 생기면 우리 덮어쓰기와 부딪힌다.

### D-26. commit 전에 발행 준비가 실패해도 확정은 되돌리지 않는다. 대신 미발행을 지표로 센다

- **상황**: 확정 트랜잭션 안에서 payload를 만들거나 afterCommit에 등록하다 예외가 난 경우다. 아직 commit 전이라, 예외를 올리면 확정까지 롤백된다.
- **결정**
  - 예외를 잡아 "미발행(등록 실패)"으로 orderId·eventId를 남기고, 확정은 commit한다.
  - 모든 미발행 경로를 Micrometer Counter `order.confirmed.unpublished`(태그 `reason`)로 센다. 경로는 등록 실패, 넘기기 실패, 큐 가득 참, 종료 중, 종료 대기 초과, 발행 예외, 전송 실패다.
- **근거**
  - `apply`는 PG가 SUCCESS를 확인한 뒤에 돈다. 우리 DB를 롤백해도 고객 결제는 취소되지 않는다. "PG 성공인데 주문은 미확정"이 남는다.
  - 이 자리의 예외는 대부분 코드 버그라 다시 해도 똑같이 터진다. 회복 작업이 재시도해도 매번 롤백되고, 결제된 모든 주문이 확정되지 못한 채 묶인다.
  - 삼키면 잃는 건 주문 DB에서 다시 만들 수 있는 이벤트 하나다(D-1의 누락 쪽). 롤백하면 잃는 건 다시 만들 수 없는 정합이다.
  - 로그만 남기면 아무도 보지 않으면 조용한 손실이 된다. Counter가 있으면 "0이 아니다"에 알림을 걸 수 있다.
- **함께 지키는 것**: 진짜 방어는 실패할 여지를 없애는 것이다. payload의 재료는 `Order`의 불변식이 보장한다. "트랜잭션 없음"은 실행 중에 우연히 생기는 일이 아니라 코드를 바꿀 때 생기는 일이므로, `apply` 경로를 실제 트랜잭션으로 지나는 통합 테스트가 잡는다.
- **7주차에 뒤집힌다**: Outbox는 이벤트 행을 같은 트랜잭션으로 쓴다. 이벤트가 주 업무의 일부가 되므로, 그때는 이벤트를 못 쓰면 롤백이 맞다. 같은 실패라도 이벤트를 어디에 두느냐에 따라 답이 바뀐다.

### D-27. 처음 사실의 정규 형식은 `orderId=…|confirmedAt=…|items=상품:수량,…`이다

- **결정**: 예 `orderId=42|confirmedAt=2026-09-07T01:00:00.123456Z|items=7:2,8:1`. items는 productId 오름차순이고, 시각은 UTC 소수 6자리다(D-6, D-25와 같은 모양).
- **근거**: conflict 기록을 읽는 운영자가 무엇이 다른지 한눈에 본다. JSON으로 남기면 필드 순서나 공백 때문에 같은 사실이 다른 글자가 될 수 있어서, 그걸 막는 규칙이 다시 필요하다.

### D-28. 락 대기 시간은 테스트에서만 줄인다

- **결정**: 실험 C-2를 위해 테스트의 consumer DB 연결에서만 `innodb_lock_wait_timeout`을 짧게(예: 2초) 둔다. 운영 값(기본 50초)은 바꾸지 않는다.
- **근거**: 운영 DB의 락 대기 정책은 이번 주 범위 밖이다.
- **한계**: 운영에서 락이 오래 잡히면 container는 최대 50초를 기다린 뒤 멈춘다.

### D-29. conflict·계약 깨짐·Duplicate가 아닌 예외는 모두 멈춤으로 보낸다

- **결정**: 분류되지 않은 예외(코드 버그 포함)는 ack하지 않고 그대로 던져 container를 멈춘다.
- **근거**: 버그는 다시 해도 실패하므로 멈춘 채로 남는다. 그래도 기록하고 ack하는 쪽은 처리하지 않은 것을 처리했다고 하는 조용한 유실이다. "모르면 멈추고 사람이 본다"(D-20)를 따른다.

### D-30. 기록하고 넘긴 기록도 지표로 센다

- **결정**: Counter `purchase.activity.rejected`를 태그 `kind`(`conflict`, `contract_violation`)로 나눠 센다.
- **근거**: conflict와 계약 깨짐은 기록하고 ack하므로 lag에 드러나지 않는다. 테이블만 쌓이고 아무도 모를 수 있다(D-8 "지표로 드러낸다", D-26과 같은 방식).
