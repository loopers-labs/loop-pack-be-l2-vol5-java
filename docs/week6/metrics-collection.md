# Kafka 성능 지표 수집

## 수집 방식

- 실험 수집기가 브로커 JMX와 Python producer·consumer client 통계를 **1초 간격**으로 읽는다. Python client는 Java JMX가 아니라 librdkafka `stats_cb`를 사용한다.
- JMX는 지표를 노출하며 저장하지 않는다. 수집기가 읽은 값을 실험 결과 파일에 기록한다.
- 실행 중인 consumer의 partition별 lag은 librdkafka 통계에서 수집한다. AdminClient lag sampler는 consumer가 내려간 상태에서도 group의 committed offset 기준 lag을 확인할 때 사용한다.
- 주문 시작부터 `ShippingRequested` 발행까지의 시간과 서비스별 수신·완료 시각은 각 서비스 DB에 기록한다. 이 값으로 p99 SLO 1초를 확인한다.
- 수집은 업무 처리 경로와 분리한다. JMX 포트는 로컬 실험 네트워크에서만 수집기가 접근하도록 둔다.

## 수집할 지표

| 대상 | 지표 | 확인할 내용 |
| --- | --- | --- |
| Broker | topic별 메시지 유입량, Produce·Fetch 요청 지연·오류, network processor 여유율 | broker 유입 및 요청 처리 병목 여부 |
| Producer | 전송 건수, 요청 지연, 전송 오류·재시도, 전송 버퍼 대기 | 발행이 지연·실패하거나 producer 내부에서 대기하는지 |
| Consumer | 소비 건수, 최대 record lag, poll 간격, offset commit 지연, 배정 partition 수 | 소비가 유입량을 따라가는지, poll·commit·assignment에 문제가 있는지 |
| Consumer group | partition별 log end offset·committed offset·계산한 lag | group별·partition별 대기량과 변화 |
| 업무 흐름 | 주문별 시작~`ShippingRequested` 시간, 서비스별 수신·완료 시간 | p99 1초 SLO 및 지연이 발생한 서비스 구간 |

JMX 지표 이름은 broker·client 버전에 따라 확인한다. 수집 시 `runId`, 수집 시각, 프로세스 역할, client-id 또는 groupId, topic·partition을 함께 기록한다. 카운터는 누적값 자체보다 이전 표본과의 차이로 1초간 처리량을 계산한다. 표본은 시뮬레이터의 `data/<run-id>/kafka-jmx.jsonl`, `kafka-clients.jsonl`, `kafka-lag.jsonl`에 출처별로 저장한다.

## 판독 기준

- lag이 증가하고 소비량이 유입량보다 낮으면 consumer 쪽 처리가 뒤처지는지 확인한다.
- 서비스 DB 처리시간이 늘면 해당 서비스 구간을 확인한다.
- 앱 처리시간은 짧은데 Producer 요청 지연이나 Broker 요청 지연이 늘면 Kafka 발행·broker 구간을 확인한다.
- 수집값 하나만으로 원인을 단정하지 않고 같은 시간대의 지표를 함께 비교한다.

## 수집기 오버헤드

먼저 1초 간격으로 필요한 지표만 수집한다. 지표 수집을 끈 실행과 켠 실행의 처리량·p99를 비교해 수집기 부하를 확인한다. 차이가 크면 수집 지표 수나 기록량을 줄인다.
