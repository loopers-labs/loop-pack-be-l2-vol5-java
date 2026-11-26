# 사고 실험 증거: 실행 `20261126-220720-066860a7`

`CheckoutIncidentTest` 를 한 번 실행해 남긴 기록이다. 테스트가 쓴 파일을 고치지 않고 그대로 옮겼다.

| 파일 | 단계 | 내용 |
|---|---|---|
| `baseline.json` | 정상 주문 10 건을 확정한 뒤 | 주문별 시도 키 · PG 주문 식별자 · 금액 · 내부 상태 · PG 거래, 생성 호출 수, 재고, health 응답, 지표 줄, 경계 로그 줄 |
| `fault.json` | 다른 주문 10 건 중 3 건의 응답을 잃은 뒤 | 위와 같다. 3 건이 `UNKNOWN` 이고 PG 거래는 10 건 모두 `SUCCESS` 다 |
| `recovery.json` | 같은 3 건을 reconcile 한 뒤 | 위와 같다. `faultRecordSha256` 은 `fault.json` 의 SHA-256 이다 |
| `follow-up.json` | 확정 20 건의 outbox 를 relay 한 뒤 | Kafka 토픽에 나간 주문 id 와 이벤트 id, outbox 상태, 같은 실행으로 잇지 않은 구간 |

## 읽는 법

- `orders[].set` 이 `baseline` 인지 `fault` 인지로 두 집합을 가른다. 복구 전후의 `fault` 집합은 같은 주문 id 와 같은 거래 키다.
- `health` 는 `/actuator/health` 응답 원문이고, `unresolvedMetricLines` 는 `/actuator/prometheus` 응답에서 그대로 옮긴 줄이다.
- `boundaryLogLines` 는 그 단계 동안 콘솔에 남은 결제 로그 원문이다. fault 단계의 `cause=timeout` 2 줄과 `cause=connection_lost` 1 줄이 응답을 잃은 3 건이다.

## 확인

```bash
shasum -a 256 fault.json
```

출력이 `recovery.json` 의 `faultRecordSha256` 과 같으면 fault 기록이 복구 뒤에 바뀌지 않은 것이다.

## 옮기지 않은 것

단계별 Prometheus 응답 전체(`*-prometheus.txt`)는 옮기지 않았다. 디스크 지표의 label 에 실행한 기기의 경로가 들어 있다. 업무 지표 줄은 위의 `unresolvedMetricLines` 에 원문 그대로 있다.

## 다시 만들기

```bash
./gradlew :apps:commerce-api:test --tests '*CheckoutIncidentTest'
```

실행마다 `apps/commerce-api/build/incident-evidence/<실행 id>/` 에 새 폴더가 생긴다.
