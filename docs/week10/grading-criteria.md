# W10 결제 사고의 관측·진단·동일 주문 복구 채점 기준

2026-11-24 제공된 [과제 원문](assignment-original.md)을 기준으로 한다. health가 UP인 동안 PG 성공 응답을 잃은 주문을 증거로 진단하고, 같은 DB·PG 거래를 보존해 추가 결제 없이 복구한 뒤 구매 활동·주간 랭킹까지 확인한다.

[학습 원문](learning-original.md)도 첨부 그대로 보존했다. 20건 사고의 관측·복구와 구매 활동·주간 랭킹 결과는 [구현 체크리스트](implementation-checklist.md), 진단·복구 절차는 [사고 runbook](../runbook/week10.md), 세 앱의 누적 회귀와 아래 10항목의 판정은 [6단계 최종 검증](final-verification.md)을 따른다.

원문 Checklist는 **10항목**이다. 아래 W10-C01~10은 원문 문구와 순서에 붙인 추적용 ID다. 항목별 구현·실제 실행 근거를 대조해 충족·부분 충족·미충족·확인 불가로 판단한다. 배점·최소 테스트 개수·성능 합격선은 원문에 제시되지 않았다. W9까지의 실행 기록은 회귀 참고이며 W10 사고 실행의 증거는 새로 확보한다.

## 원문 10항목과 확인 증거

| ID | 원문 체크 항목 | 충족을 확인할 증거 |
| --- | --- | --- |
| W10-C01 | 실제 health·업무 지표·식별자별 경계 기록으로 증상을 설명한다. | baseline·fault·recovery 각각의 실제 Actuator health와 Prometheus 응답, 관찰 시각, DB 상태에 근거한 업무 지표의 변화를 확인한다. orderId·attemptKey·providerOrderId·거래 키로 adapter 결과, 내부 적용 및 후속 사실을 연결한다. |
| W10-C02 | 최소 두 가설의 예상과 실제를 비교하고 부족한 증거도 표시한다. | 조치 전에 최소 두 가설과 예상 관찰을 적고 읽기 전용 PG 조회·adapter·TX 기록과 대조한다. PG SUCCESS와 내부 UNKNOWN만으로 원인을 확정하지 않으며 부족한 증거는 미확정으로 남긴다. |
| W10-C03 | 비밀값·개인정보를 남기지 않고 metric label의 무한 증가를 피한다. | 합성 데이터와 접근 제한된 개별 기록을 사용한다. metric label은 유한한 상태·결과 등으로 제한하고 orderId·userId·traceId를 넣지 않는다. 관리 endpoint의 로컬 범위와 기존 접근 보호를 유지한다. |
| W10-C04 | 같은 DB·PG 거래 상태를 유지해 baseline→fault→recovery를 이어 간다. | 한 lifecycle의 정상 10건·별도 장애 10건을 실제 API로 준비한다. ID로 고정한 장애 3건은 PG SUCCESS 저장 뒤 응답을 잃는다. 세 단계 사이 DB·거래를 초기화하지 않고 baseline·recovery의 주입 OFF와 finally 해제를 확인한다. |
| W10-C05 | 장애 집합은 7·UNKNOWN 3·PG 성공 10이며 같은 집합을 10·0·10으로 복구했다. | fault 전에 고정한 장애 주문 10개의 동일 ID 집합을 새 DB 조회와 PG 거래로 대조한다. fault의 내부 성공 7·UNKNOWN 3·PG SUCCESS 10이 recovery에서 10·0·10이며 누적 거래는 20을 유지한다. |
| W10-C06 | create 추가 0, 예약·재고·포인트·쿠폰 불변 조건과 중복 적용 방지를 확인했다. | 기존 본인 주문 reconcile HTTP를 사용하고 복구 중 PG create 호출 증가는 0이다. UNKNOWN 동안 예약 유지, 성공 반영 때 추가 재고 차감 0, 포인트·쿠폰 보존을 확인한다. 반복 reconcile/callback의 주문 확정·예약 소비·Outbox 추가 효과와 타인 접근을 검증한다. |
| W10-C07 | 복구 주문의 실제 Outbox·Kafka·구매 활동·주간 랭킹 API를 연결했다. | 같은 장애 주문의 실제 relay·Kafka·consumer를 거친 활동의 품목·수량을 대조한다. 전체 실제 주간 입력을 새 W9 snapshot으로 고정해 job과 고객 랭킹 HTTP를 확인하고 같은 주 baseline 활동도 포함한다. |
| W10-C08 | fault 원문을 보존하고 runbook에 중단·재진입 조건을 남겼다. | 실행별 증거 위치에 단계별 원문·중간 실패를 보존한다. 복구 전후 동일 식별자·효과 수를 assertion하고 fault 기록의 보존을 확인한다. `docs/runbook/week10.md`에 증상·진단 질의·조치 전제·중단 조건·복구 확인·재진입 절차를 작성한다. |
| W10-C09 | 관련 누적 회귀·lint·ArchUnit 결과와 미검증 위험을 구분했다. | 이번 사고와 W5~W9 및 영향받은 기존 상품·브랜드·좋아요·관리자 기능의 관련 회귀를 실행한다. 실제 테스트 수·실패/오류/skip·종료 결과, Java Checkstyle·ArchUnit과 기능별 검증 범위·미확인 위험을 남긴다. |
| W10-C10 | 자신이 시작한 테스트 자원만 정리했으며 운영 완료를 주장하지 않는다. | 성공·중간 실패 뒤 자신이 켠 주입·보류와 서버·consumer·스레드·컨테이너를 정리한 근거를 확인한다. 공유 인프라·다른 실행의 데이터를 보호하고 실제로 확인한 로컬 통제 사고·관련 회귀 범위를 명시한다. |

## 반드시 대조할 주문 집합과 수치

| 단계 | 집계 대상 | 내부 성공 | UNKNOWN | 대상 PG SUCCESS 거래 | 전체 누적 PG 거래 |
| --- | --- | ---: | ---: | ---: | ---: |
| baseline | 정상 주문 10개 | 10 | 0 | 10 | 10 |
| fault | 별도 장애 주문 10개 | 7 | 3 | 10 | 20 |
| recovery | 같은 장애 주문 10개 | 10 | 0 | 10 | 20 |

실제 생성한 orderId·paymentAttemptKey·providerOrderId·거래 키와 계정·주문 금액을 목록으로 연결한다. 세 장애 주문은 생성된 ID로 지정해 실행 순서의 우연을 배제한다. fixture의 거래 수와 create 호출 수는 각각 기록하며, 중복 create 시도도 기록하고 테스트를 실패시킨다. 호출이 오류를 반환했더라도 시도 기록은 보존한다.

현재 코드에서 주문 진행 상태는 `OrderStatus.PAYMENT_PENDING`, 결과를 확인하지 못한 결제 시도는 `PaymentAttemptStatus.UNKNOWN`이다. 원문의 UNKNOWN 3건은 이 실제 주문·시도 관계로 설명하고 집계한다. 성공도 주문의 CONFIRMED와 결제 시도의 성공·예약 소비 사실을 함께 대조한다. [주문 상태](../../apps/commerce-api/src/main/java/com/loopers/domain/order/OrderStatus.java) · [결제 시도 상태](../../apps/commerce-api/src/main/java/com/loopers/domain/payment/PaymentAttemptStatus.java)

정상 7건은 처음 수락된 PENDING 이후 기존 조회/알림 경로로 SUCCESS를 확인한다. 장애 3건은 PG에 SUCCESS가 남고 생성 응답을 잃은 뒤, fault 관찰까지 해당 callback과 구현돼 있는 자동 reconcile을 보류한다. commerce DB에 UNKNOWN·CONFIRMED를 직접 입력해 사고를 만들지 않는다. 실험 전 시작 재고와 두 주문 집합의 예약 수량을 계산한다.

현재 최초 confirm의 `startAndAwait`도 생성 응답 유실 뒤 제한된 PG 조회로 결과를 회복할 수 있다. fault 3건을 관찰할 때는 이 경로까지 테스트 경계에서 통제하고, 이후 원래 결과 조회·적용 기능을 이용해 복구한다. [현재 confirm 처리](../../apps/commerce-api/src/main/java/com/loopers/application/order/OrderPaymentCoordinator.java)

기존 `LocalTestPg`에는 응답 유실·거래 상태·create 호출 집계가 있다. W10에서는 실제 ID별 주입과 중복 create 시도 검증을 사고 범위에 연결한다. 기존 전역 one-shot 설정을 호출 순서만으로 맞추거나 단계 사이 `reset()`으로 거래·호출 수를 지우지 않는다. adapter가 관찰한 NETWORK·TIMEOUT을 실제 기록대로 구분하고, 상태 관찰 시각과 발송 시각도 각각의 의미로 기록한다. [기존 PG fixture](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/payment/support/LocalTestPg.java) · [PG adapter](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/payment/HttpTestPgClient.java)

## 관측과 진단의 기준

업무 지표 하나는 실제 DB의 현재 미종결 결제 상태를 반영해야 한다. metric 이름·설명, UNKNOWN만 셀지 PENDING도 포함할지, 관찰 범위·갱신 주기를 설계에 적는다. UNKNOWN만 세는 격리된 실험 범위라면 안정화된 단계 값은 0 → 3 → 0이다. 다른 상태도 포함하면 해당 범위의 실제 기대값을 정하고 유한한 대기 안에서 확인한다.

실제 management 주소의 health·Prometheus 응답을 관찰 시각과 함께 보존한다. 테스트에는 사용 가능한 별도 management port를 주입한다. 상태 JSON을 Prometheus 출력으로 꾸미거나 health 출력값을 수정하지 않는다. health가 예상과 다르면 설정·의존성과 영향을 조사해 실제 관찰 결과를 기록한다. [기존 monitoring 설정](../../supports/monitoring/src/main/resources/monitoring.yml)

| 가설 예시 | 예상 관찰 | 판정에 필요한 기록 |
| --- | --- | --- |
| PG 처리 지연 | 권위 조회가 PENDING | 같은 계정·주문·금액의 거래 조회 결과와 시각 |
| PG 성공 응답 유실 | PG SUCCESS·내부 UNKNOWN·생성 응답 유실/timeout | 거래와 adapter 통신 결과, 내부 주문·시도 상태 |
| 내부 성공 반영 실패 | PG SUCCESS·내부 적용 시도·DB rollback | 권위 조회, 결과 적용 경계·실패 및 예약/Outbox 상태 |

위 예시 중 최소 두 가설 또는 동등한 경쟁 가설을 비교한다. 각 가설을 모두 별도 장애로 주입하는 요구는 없다. 진단 거래 조회는 commerce 상태를 바꾸지 않는 읽기다. fault 증거를 보존한 뒤 범위·중단 조건을 사람이 확인하고 복구 조치를 수행한다. 연결 테스트에서는 이 순서와 대상 목록을 명시한다. 주입 코드의 의도나 AI의 확신만으로 원인을 판정하지 않는다.

## 복구와 후속 결과

새 실험 입력을 멈추고 영향받은 세 주문을 고정한 뒤 응답 유실을 해제한다. 현재 본인 주문 복구 진입점은 `POST /api/v1/orders/{orderId}/payment/reconcile`이다. 실제 controller·adapter·DB 경로에서 같은 계정·주문·금액·거래의 검증된 SUCCESS를 기존 결과 적용 경계로 반영한다. [복구 controller](../../apps/commerce-api/src/main/java/com/loopers/interfaces/api/order/OrderPaymentRecoveryController.java)

조회 실패·0건·복수 거래·금액 불일치·계속 PENDING이면 유한한 조회 후 상태를 보존하고 중단한다. 새로운 PG create나 DB 강제 성공 처리로 불확실성을 덮지 않는다. 반복 callback·재조회와 다른 사용자의 접근에는 기존 W5 보호를 적용한다.

UNKNOWN 동안 확보한 예약은 유지하고, 성공 반영은 해당 예약을 소비한다. 복구로 재고를 다시 차감하거나 기존 포인트·쿠폰을 바꾸지 않는다. 주문 확정·예약 소비·Outbox는 기존 공동 TX와 중복 보호를 유지한다.

주문들이 같은 주에 처음 확정되도록 테스트 clock과 기간을 정한다. 실제 구매 활동을 W9의 새 snapshot에 고정한다. 같은 주에 baseline 활동이 존재하면 함께 집계한다. 같은 상품을 주문마다 1개씩 구매하고 이 20건만 그 주에 있다면 최종 점수는 20이다. 다른 상품·수량·주간 입력을 택했다면 그 전체 입력으로 기대 점수·순위를 계산한다. 이미 공개된 snapshot은 수정하지 않는다.

주간 소속은 내부에 최초 저장된 confirmedAt을 따른다. 복구로 처음 확정되는 주문이 주 경계를 넘으면 그 실제 값으로 주를 정하고, 기존 confirmedAt을 PG 요청 시각이나 복구 시각으로 덮어쓰지 않는다. Kafka 발행 retry record 수와 DB 구매 활동 수를 따로 확인한다.

## 증거·검사·제출

실행별로 새 증거 위치를 준비하고 다음 사실을 연결한다.

- 실행 식별·단계·시각, 정상 10건과 장애 10건의 서로 다른 ID 집합.
- 각 주문의 시도·PG 거래·금액·내부 상태·예약·create 호출 수.
- 실제 health·Prometheus 원문, 가설을 구분하는 adapter·적용·rollback 경계 기록.
- 조치 전에 작성한 가설별 예상·실제·판정과 중단 조건.
- 복구 전후 같은 주문·거래, create 증가 0, 재고·포인트·쿠폰 및 중복 효과 대조.
- 실제 Outbox·소비 활동과 snapshot/revision·전체 주간 점수·HTTP 결과.
- 보존된 fault 원문과 중간 실패, 주입·보류 해제 및 소유 자원 정리 결과.

파일 존재나 marker만으로 통과시키지 않는다. 테스트 assertion이 식별자·상태·효과 수를 확인해야 한다. 파일명·JSON 형식은 선택 사항이며 fault 파일 hash는 재현 자료를 구분하는 보조 근거로 사용할 수 있다.

현재 Java 프로젝트의 최종 검사 범위는 다음 명령을 바탕으로 실제 변경 모듈에 맞춘다.

```sh
./gradlew :apps:commerce-api:check :apps:commerce-streamer:check :apps:commerce-batch:check
```

새 사고 증거를 확보할 때는 해당 테스트를 실제로 실행하고, Gradle이 이전 결과를 재사용하면 대상 task의 `--rerun` 또는 검사 명령의 `--rerun-tasks` 등으로 새 실행을 확인한다. 0건·skip·연결 실패를 성공으로 표시하지 않는다. W9의 chunk 재시작·늦은 revision, W8 쿠폰, W7 Outbox/소비, W5 PG/예약 및 영향받은 기존 기능 회귀도 확인한다. 모듈을 추가로 바꾸면 해당 검사까지 연결한다.

제출물은 관측·작은 사고 fixture·연결 테스트 코드와 PR, 사고 증거, `docs/runbook/week10.md`, 기능별 검증 범위·남은 위험, 기술 글이다. runbook은 증상·진단 질의·조치 전제·중단 조건·복구 확인·재진입 순서를 담는다. 같은 사건의 재진입에서도 보존된 거래와 증거를 확인한다.

## 구현자가 선택할 부분

업무 지표 이름·상태 범위·갱신 주기, 세 장애 주문의 지정 방식, fixture 제어·관측 위치, 증거 파일 구성과 가설의 표현을 정한다. 단일 통합 테스트는 같은 lifecycle을 유지하는 구현 선택이며 클래스·메서드 이름은 자유다. 배치는 테스트 구성 재사용 또는 별도 실행으로 연결할 수 있다. 분산 trace 수집·backend 구축은 선택이며 구현했다면 실제 PG/Kafka 문맥 연결을 검증한다. W5 수동 reconcile을 사용하는 프로젝트에 새 자동 scheduler를 요구하지 않는다.

W10은 로컬 합성 데이터·통제된 응답 유실 사고를 다룬다. 실제 카드·실계정·외부 PG 장애 주입·운영 DB 변경, 관리 endpoint 인터넷 공개나 env·heapdump 노출은 하지 않는다. 검증한 통제 사고와 미확인 운영·부하·다른 장애 범위를 제출 설명에 명시한다.
