# W6 복제 실험 결과 (선택 심화 13)

- 실행: 2026-10-30T04:45:41Z · 별도 compose(controller 1 · broker 3, Kafka 3.5.1 KRaft)
- topic `replication-exp-20261030134532`: partition 1 · 복제 수 3(broker 1 · 2 · 3, 선호 leader 1) · `min.insync.replicas=2` · unclean leader election 꺼짐
- producer 는 broker 컨테이너 안의 `kafka-producer-perf-test.sh`(record 100 바이트, `linger.ms=0`, `request.timeout.ms=5000`)다. 끝 offset 은 `kafka-get-offsets.sh`(high watermark)로 읽는다

## 시작 상태

```
Topic: replication-exp-20261030134532 Partition: 0 Leader: 1 Replicas: 1,2,3 Isr: 1,2,3
```

## E1. 모두 정상 · acks=all

```
100 records sent, 99.900100 records/sec (0.01 MB/sec), 16.32 ms avg latency, 177.00 ms max latency, 4 ms 50th, 67 ms 95th, 177 ms 99th, 177 ms 99.9th.
```
오류 0 · 끝 offset 100 · Isr: 1,2,3

## E2. follower 1 대 정지(broker 3) · acks=all

```
100 records sent, 99.403579 records/sec (0.01 MB/sec), 9.80 ms avg latency, 171.00 ms max latency, 4 ms 50th, 27 ms 95th, 171 ms 99th, 171 ms 99.9th.
```
오류 0 · 끝 offset 200 · Isr: 1,2

## E3. follower 2 대 정지(broker 2 · 3) · acks=all 과 acks=1

```
0 records sent, 0.000000 records/sec (0.00 MB/sec), NaN ms avg latency, 0.00 ms max latency, 0 ms 50th, 0 ms 95th, 0 ms 99th, 0 ms 99.9th.
```
acks=all: 실패 20 건 · broker 응답 `NOT_ENOUGH_REPLICAS` 97 회(producer 가 재시도하다 `delivery.timeout.ms` 에 `TimeoutException` 로 포기) · 끝 offset 200 → 200 · Isr: 1
```
20 records sent, 20.576132 records/sec (0.00 MB/sec), 11.30 ms avg latency, 139.00 ms max latency, 3 ms 50th, 139 ms 95th, 139 ms 99th, 139 ms 99.9th.
```
acks=1: 오류 0 · 끝 offset 200 → 220

## E4. acks=1 · 복제 전에 leader 강제 종료

follower(2 · 3)를 `docker pause` 로 멈춰 복제를 막고 acks=1 로 50 건을 보낸 뒤(멈춘 동안 producer 는 leader 에만 붙는다), leader(1)를 `docker kill` 하고 follower 를 되살린다. 멈춤을 KRaft 의 `broker.session.timeout.ms`(9 초) 안에 끝내 follower 가 ISR 에 남아 있게 한다 — 그래야 unclean 선출 없이 follower 가 새 leader 가 된다.
```
50 records sent, 378.787879 records/sec (0.04 MB/sec), 10.22 ms avg latency, 129.00 ms max latency, 8 ms 50th, 9 ms 95th, 129 ms 99th, 129 ms 99.9th.
```
ack 받은 record 50 건 · 끝 offset 220 → 220 · 새 Leader: 2 · **잃은 record 50 건**
broker 1 이 돌아온 뒤 끝 offset 220 — 돌아온 옛 leader 는 새 leader 의 log 에 맞춰 자기 log 를 잘라낸다

## E5. acks=1 대신 acks=all · 같은 장애

E4 와 같은 순서다. 멈춘 동안 producer 는 leader(1)에만 붙는다. follower 가 멈춘 동안 보낸 record 는 복제되지 않아 ack 를 받지 못하고 `delivery.timeout.ms`(4 초)에 실패한다.
```
0 records sent, 0.000000 records/sec (0.00 MB/sec), NaN ms avg latency, 0.00 ms max latency, 0 ms 50th, 0 ms 95th, 0 ms 99th, 0 ms 99.9th.
```
ack 받은 record 0 건 · 끝 offset 220 → 220 · ack 받고 잃은 record 0 건 — 복제되지 않은 record 에는 ack 가 가지 않았다

## E6. ISR 이 leader 하나로 줄어든 뒤 leader 가 죽으면

follower(2 · 3)를 `broker.session.timeout.ms`(9 초)보다 오래(12 초) 멈춰 controller 가 둘을 ISR 에서 빼게 한 뒤 leader(1)를 `docker kill` 한다.
leader 를 죽이고 15 초 뒤: Leader: none Replicas: 1,2,3 Isr: 1 — ISR 에 남은 replica 가 없어 unclean 선출(꺼짐) 없이는 leader 를 고르지 못한다. partition 이 읽기도 쓰기도 되지 않는다(가용성을 잃고 정합성을 지킨다)

## E7. leader 교체 중 보내기 · acks=all

20 건/초로 200 건을 보내는 동안 3 초 뒤 leader(1)를 `docker kill` 한다. `delivery.timeout.ms` 는 30 초다.
```
200 records sent, 7.964954 records/sec (0.00 MB/sec), 13725.50 ms avg latency, 22419.00 ms max latency, 17491 ms 50th, 21967 ms 95th, 22375 ms 99th, 22419 ms 99.9th.
```
오류 0 · 끝 offset 220 → 420 · 새 Leader: 2
