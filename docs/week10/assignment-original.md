## 💻 Implementation Quest

> **health가 정상인 결제 사고에서 증거로 원인을 구분하고, 같은 주문을 추가 결제 없이 복구합니다.**
> 

W5 PG 주문·W7 Outbox·구매 활동·W9 주간 랭킹을 연결해 사고를 재현합니다. W8 쿠폰 발급·본인 목록은 그대로 보존합니다. 이번에 추가할 것은 작은 사고 fixture, 필요한 관측, 연결 테스트와 runbook입니다.

### 🧭 1. 사고 범위와 정상 기준선

고객은 “결제 확인 중”인 주문을 보고 있지만 프로세스 health는 UP인 상황입니다. 실험에서는 **PG는 SUCCESS인데 응답을 잃어 내부 주문이 UNKNOWN으로 남는 경계**를 통제합니다.

- 기존 상품·사용자·주문 API와 실제 DB 상태를 사용합니다.
- W5에서 작성한 로컬 PG HTTP fixture와 동일 계정·주문·금액 계약을 재사용합니다.
- PG 요청을 받은 뒤 거래를 저장하고 응답을 유실하는 기능이 없다면 fixture에 작은 제어를 추가합니다.
- W5의 결과 조회·callback 검증·예약 소비/반환·중복 방지는 약화하지 않습니다.
- 실제 카드·계정·외부 PG 장애 주입·운영 DB 변경은 하지 않습니다.

서로 다른 정상 주문 10건과 장애 주문 10건을 준비합니다. ID 문자열을 임의의 정해진 값으로 덮어쓰기보다 실제 생성한 ID·paymentAttemptKey·providerOrderId·거래 키를 목록으로 묶습니다. PG 계약의 합성 사용자 범위는 유지하고 주문별 저장 금액을 대사합니다.

같은 상품·수량으로 작은 기대값을 만들 수 있습니다. 예를 들어 각 주문에 같은 공개 상품 1개씩을 담되 baseline과 fault의 주문 집합을 분리합니다. 재고는 두 집합의 예약을 감당하도록 준비하고 기대 가용 수량을 시작 전에 계산합니다.

| 단계 | 대상 주문 | 내부 성공 | UNKNOWN | PG SUCCESS 거래 | 누적 PG 거래 |
| --- | --- | --- | --- | --- | --- |
| baseline | 정상 집합 10개 | 10 | 0 | 10 | 10 |
| fault | 별도 장애 집합 10개 | 7 | 3 | 10 | 20 |
| recovery | 같은 장애 집합 10개 | 10 | 0 | 10 | 20 |

각 단계의 health 응답과 관찰 시점을 기록합니다. 이 실험은 의존 서비스 자체를 내리지 않으므로 health UP과 업무 미완료를 함께 관찰합니다. health가 다르게 나오면 출력값을 고치지 말고 실제 설정·의존성·영향을 확인합니다.

### 🔭 2. 필요한 관측을 연결하기

두 트랙의 `supports/monitoring`에는 Actuator와 Prometheus registry 의존성이 있습니다. `monitoring.yml`은 health·prometheus를 노출하고 기본 management port를 8081로 둡니다. **업무별 지표·사고 fixture·완성된 분산 추적은 제공된 기능이 아닙니다.**

| 질문 | 필요한 기록 |
| --- | --- |
| 어떤 주문이 미확인인가 | orderId·attemptKey·상태·상태 시각 |
| PG에서 거래가 생겼는가 | 같은 계정/주문/금액의 거래 키·최종 상태·create 수 |
| 어디에서 결과를 잃었는가 | adapter timeout 또는 조회 결과, 내부 적용·rollback 기록 |
| 이후 업무는 어디까지 진행했는가 | Outbox 상태·eventId·구매 활동·snapshot/publication |

특정 주문을 연결하는 구조화 로그를 실제 경계에 남기고, 현재 미확인 결제 수를 알려 주는 **업무 지표 하나**를 추가합니다. 예를 들어 `commerce.payment.unresolved` gauge를 실제 DB의 미종결 상태에서 갱신할 수 있습니다. UNKNOWN만 센다면 PENDING 포함 지표와 구분해 이름·설명을 정합니다.

지표의 관찰 범위·갱신 주기를 명시합니다. DB 조회 결과를 작은 주기로 갱신한 gauge라면 지연을 인정하고, 테스트는 유한한 대기 안에서 실제 값이 바뀌는지 확인합니다. 상태 JSON의 숫자를 Prometheus 원문인 것처럼 저장하지 않습니다.

- metric label은 유한한 상태·결과 정도로 제한합니다. orderId·userId·traceId를 label로 만들지 않습니다.
- 개별 식별자는 접근 제한된 합성 로그·상태 기록에서 연결합니다.
- 실제 Actuator의 health와 Prometheus 응답을 가져오고 추가한 업무 지표가 존재·변화하는지 확인합니다.
- 테스트 management port는 사용 가능한 별도 포트로 정하고 실제 주입된 주소를 사용합니다. 8081이 항상 비어 있다고 가정하지 않습니다.
- 관리 endpoint를 인터넷에 공개하거나 `env`·`heapdump`까지 열지 않습니다. 로컬 테스트 범위와 기존 접근 보호를 유지합니다.

분산 trace 수집·backend 구축은 선택입니다. 구현했다면 실제 PG/Kafka 문맥 연결과 끊기는 구간을 확인하고, 하지 않았다면 로그 상관관계만으로 분산 추적을 완료했다고 쓰지 않습니다.

### 🧪 3. 동일 상태를 유지하는 사고 실험

한 테스트 lifecycle에서 **baseline → fault → 증거 보존 → recovery**를 이어갑니다. 같은 DB와 테스트 PG 거래 상태를 유지하는 단일 통합 테스트가 간단한 선택입니다. 정확한 클래스·메서드 이름은 자신의 구조에 맞게 정합니다.

- 공통 · 단계별 제어
    
    ```
    BASELINE
      응답 유실 OFF
      정상 주문 10개 수락 → fixture SUCCESS → 정상 조회/알림 → 내부 확정
      baseline 증거 보존
    
    FAULT
      장애 주문 10개 중 고정한 3개만 거래 SUCCESS 저장 뒤 생성 응답 유실
      해당 3개의 callback 전달과 자동 reconcile은 관찰 시점까지 보류
      다른 7개는 정상 최종 결과 적용
      fault 상태·health·metric·경계 로그 보존
    
    RECOVERY
      응답 유실 OFF, 같은 거래와 DB 상태 유지
      같은 3개 주문의 기존 reconcile API 호출
      동일 거래 권위 조회 → 내부 적용 → Outbox/소비 → 새 랭킹 snapshot
      create 추가 0과 같은 주문의 결과 확인
    ```
    
    callback·자동 조회 보류는 테스트 경계의 제어이며 제품 복구 기능을 삭제하는 것이 아닙니다. 자동 reconcile을 선택 구현한 경우에만 해당 테스트 실행에서 제어합니다. W5 수동 reconcile 기본 구현에 새 scheduler를 요구하지 않습니다.
    

세 장애 주문은 실행 순서의 우연에 의존하지 않도록 실제 ID 목록으로 지정합니다. fixture는 거래 수·생성 호출 수·상태를 유지하며, 중복 create 시도를 기록하고 테스트를 실패시킵니다. 오류를 반환했다고 두 번째 호출 사실을 지우지 않습니다.

첫 거래 수락의 PENDING과 최종 SUCCESS는 구분합니다. 정상 7건은 기존 조회/알림 경로로 SUCCESS를 확인해 확정하고, 장애 3건은 외부 SUCCESS 사실을 저장한 뒤 결과를 잃도록 만듭니다. 이 상태를 만들기 위해 commerce DB에 UNKNOWN·CONFIRMED를 직접 적지 않습니다.

baseline과 recovery에서 응답 유실이 꺼져 있는지 assert합니다. 실패 시에도 finally로 자신이 켠 주입과 보류를 해제하되 거래·원문 증거를 초기화해 성공으로 바꾸지 않습니다.

### 🔎 4. 경쟁 가설을 증거로 판단하기

조치 전에 최소 두 가설과 예상 관찰을 적습니다. 다음 세 가지를 사용할 수 있습니다.

| 가설 | 예상 | 확인 |
| --- | --- | --- |
| PG 처리 자체가 지연 | 권위 조회 PENDING | 해당 거래 조회 |
| PG 성공 응답 유실 | PG SUCCESS·내부 UNKNOWN·생성 응답 timeout | 거래·adapter 결과·주문 |
| 성공 결과의 내부 반영 실패 | PG SUCCESS·적용 시도·DB rollback | 조회 결과·TX 실패·예약/Outbox |

PG SUCCESS와 UNKNOWN의 조합만으로 응답 유실과 DB 적용 실패를 구분하지 않습니다. 실제 경계 로그·TX 결과까지 연결하고 증거가 부족하면 “미확정”이라고 적습니다.

진단용 거래 조회는 읽기만 수행하고 commerce 상태를 바꾸지 않아야 합니다. 실제 reconcile은 결과를 적용하므로 **fault 상태 보존 전에 복구 API를 호출하지 않습니다.**

AI에게는 합성 식별자의 원문 기록과 자신이 적은 가설을 제공해 예상·실제·판정을 비교하게 할 수 있습니다. 주입 코드의 정답이나 AI의 확신만으로 원인을 확정하지 않고, 상태 변경 조치는 사람이 범위·중단 조건을 확인한 뒤 수행합니다.

### 🧯 5. 같은 주문을 제한적으로 복구하기

새 실험 입력을 멈추고 영향을 받은 세 주문 목록을 고정합니다. response-drop 제어를 해제한 뒤 W5의 **본인 주문 reconcile API**를 실제 controller·adapter·DB 경로로 호출합니다.

- PG create를 다시 호출하지 않습니다.
- 동일 계정·주문·금액·거래를 검증한 SUCCESS만 기존 결과 적용 경계로 처리합니다.
- 조회 실패·0건·복수 거래·금액 불일치·계속 PENDING은 임의 성공으로 바꾸지 않습니다. 유한한 조회 후 상태를 보존하고 중단합니다.
- callback이나 재조회가 반복돼도 주문 확정·예약 소비·Outbox 사실이 늘지 않습니다.
- 다른 사용자의 주문 조회·조정이 허용되지 않는 기존 보호를 유지합니다.

복구 뒤 장애 집합 **동일한 10개 주문**의 상태를 새 DB 조회로 확인합니다. baseline이나 새 주문을 섞어 10건을 채우지 않습니다.

| 검증 | 기대 |
| --- | --- |
| 장애 집합 내부 성공·UNKNOWN | 10·0 |
| 장애 집합 PG 거래 | 같은 10개, 모두 검증한 SUCCESS |
| 복구 중 create 호출 증가 | 0 |
| 재고 | UNKNOWN 동안 예약 유지, 성공 때 추가 차감 없음 |
| 기존 포인트·쿠폰 | PG 복구로 변하지 않음 |
| 반복 reconcile/알림 | 동일한 업무 사실로 수렴 |

### 🔗 6. 복구된 사실의 후속 결과 확인

같은 장애 집합의 주문이 남긴 Outbox를 실제 relay·Kafka·consumer로 처리합니다. 각 주문의 품목 수·quantity에 맞는 활동이 한 번씩 저장되는지 확인합니다. 발행 retry record 수와 업무 활동 수를 혼동하지 않습니다.

이 실제 활동을 W9의 새 snapshot으로 고정해 주간 job을 실행하고 랭킹 API의 점수·순위를 확인합니다. 이미 공개된 snapshot을 직접 수정하지 않습니다. 주문들이 같은 주에 처음 확정되도록 테스트 clock과 기간을 정하고, baseline 활동이 같은 주에 있다면 기대 점수에도 포함합니다.

예시처럼 같은 상품을 정상 10개·장애 10개 주문에서 1개씩 구매했다면, 복구 후 그 주의 전체 입력이 이 20개뿐일 때 상품 점수는 20입니다. cohort별 증거는 나누더라도 **주간 집계가 baseline을 몰래 제외하지 않게** 합니다. 다른 입력을 준비했다면 실제 전체 입력의 기대값으로 계산합니다.

내부 최초 confirmedAt이 주 경계를 넘는 경우에는 그 값의 주간 소속을 따릅니다. PG 요청 시각이나 복구 실행 시각으로 이미 저장한 confirmedAt을 덮어쓰지 않습니다.

이 연결 테스트와 기존 W9 실패·재시작 테스트는 다른 질문을 검증합니다. 이번 사건이 복구됐다는 이유로 chunk restart·늦은 revision 회귀를 생략하지 않습니다.

### 🗂 7. 증거와 Runbook

새 실행마다 별도의 증거 위치를 만들고 세 단계의 기록을 보존합니다. 파일 이름과 JSON 구조는 자유롭게 정하되 다음 내용을 연결할 수 있어야 합니다.

- 실행·시각·단계와 baseline/fault 주문 집합
- 각 주문의 시도·PG 거래·내부 상태·예약·생성 호출 수
- 실제 health·Prometheus 응답과 가설을 구분한 경계 로그
- 복구 전후 같은 ID 집합, create 추가 0, 후속 활동·랭킹 결과
- fault 기록을 덮어쓰지 않았다는 확인과 중간 실패 기록

marker나 파일 존재만으로 통과시키지 않습니다. 테스트 assertion이 같은 식별자·상태·효과 수를 검증해야 합니다. fault 파일을 사용했다면 hash를 함께 기록해 재현에 쓴 파일을 구분할 수 있습니다.

`docs/runbook/week10.md`에 증상·진단 질의·조치 전제·중단 조건·복구 확인·재진입 절차를 씁니다. 외부 거래가 불확실한 상태에서 DB를 강제로 바꾸거나 재결제하는 명령을 넣지 않습니다.

### 🧪 8. 누적 기능과 검증 범위를 구분하기

현재 작업물 기준으로 관련 회귀를 실행하고, 기능별로 이번에 확인한 증거와 미확인 위험을 짧게 정리합니다.

| 기능 | 확인할 기존 계약 |
| --- | --- |
| 상품·브랜드·좋아요·관리자 | 조회·삭제·소유권·과거 주문 보존·캐시 보호 |
| 포인트·주문·PG | 포인트 기록 보존, PG 단일 시도·예약·조회 조정 |
| 이벤트·Outbox | 실제 구매 활동, 발행 gap 복구·소비 중복 방지 |
| 쿠폰 | 총량·사용자 중복·동시 실행 수용·본인 목록 |
| 랭킹 | 실제 입력·같은 instance 재시작·revision·공개 API |
| 이번 사고 | 응답 유실 진단·같은 주문 복구·후속 결과 |
- Java·Kotlin · 관련 검사 예시
    
    실제 작성한 테스트 이름·모듈에 맞춰 실행합니다. 연결 테스트의 배치 코드는 테스트 구성을 통해 재사용하거나 별도 실행을 조율할 수 있으며, production application끼리의 의존성을 억지로 추가하지 않습니다.
    
    ```bash
    ./gradlew :apps:commerce-api:test --tests '*CheckoutIncidentTest'
    ./gradlew :apps:commerce-api:test --tests '*PaymentIntegrationTest'
    ./gradlew :apps:commerce-api:test --tests '*CouponIssueConcurrencyTest'
    ./gradlew :apps:commerce-batch:test --tests '*RankingJobRestartTest'
    
    # Java: 기존 Checkstyle·ArchUnit·관련 회귀
    ./gradlew :apps:commerce-api:check :apps:commerce-streamer:check :apps:commerce-batch:check
    
    # Kotlin
    ./gradlew :apps:commerce-api:ktlintCheck :apps:commerce-streamer:ktlintCheck :apps:commerce-batch:ktlintCheck
    ./gradlew :apps:commerce-api:check :apps:commerce-streamer:check :apps:commerce-batch:check
    ```
    
    다른 모듈도 변경했다면 해당 검사와 회귀를 포함합니다. 테스트 0건·skip·연결 실패는 통과가 아닙니다. 실제 새 실행의 결과를 사용하고 예전 UP-TO-DATE 기록을 새 사고 증거로 쓰지 않습니다.
    

W5 fixture와 실제 MySQL·Redis·Kafka 등 자신이 구성한 테스트 의존성을 준비합니다. 끝날 때는 **이번 실행이 소유한** 서버·consumer·스레드·컨테이너만 정리합니다. 공유 인프라나 다른 실행의 DB를 일괄 종료하지 않습니다.

**제출**: 관측·작은 사고 fixture·연결 테스트 코드와 PR, 사고 증거, runbook, 기능별 검증 범위·남은 위험, 기술 글. 로컬 통제 사고·관련 회귀 성공과 운영 배포·실계정·부하·모든 장애 검증을 구분합니다.

---

## ✅ Checklist

### 🔭 관측과 진단

- [ ]  실제 health·업무 지표·식별자별 경계 기록으로 증상을 설명한다.
- [ ]  최소 두 가설의 예상과 실제를 비교하고 부족한 증거도 표시한다.
- [ ]  비밀값·개인정보를 남기지 않고 metric label의 무한 증가를 피한다.

### 💳 동일 업무 복구

- [ ]  같은 DB·PG 거래 상태를 유지해 baseline→fault→recovery를 이어 간다.
- [ ]  장애 집합은 7·UNKNOWN 3·PG 성공 10이며 같은 집합을 10·0·10으로 복구했다.
- [ ]  create 추가 0, 예약·재고·포인트·쿠폰 불변 조건과 중복 적용 방지를 확인했다.
- [ ]  복구 주문의 실제 Outbox·Kafka·구매 활동·주간 랭킹 API를 연결했다.

### 🗂 증거와 종료

- [ ]  fault 원문을 보존하고 runbook에 중단·재진입 조건을 남겼다.
- [ ]  관련 누적 회귀·lint·ArchUnit 결과와 미검증 위험을 구분했다.
- [ ]  자신이 시작한 테스트 자원만 정리했으며 운영 완료를 주장하지 않는다.