# 6~7주차 주문 전달 복구

## 같은 실행에서 재현

Windows PowerShell 7, JDK 21, Docker를 사용한다. 저장소 루트에서 현재 실행 파일과 보존한 직접 발행 파일을 따로 빌드한다.

```powershell
./gradlew.bat :apps:commerce-api:bootJar :apps:commerce-streamer:bootJar :apps:test-pg:bootJar
$root = (Get-Location).Path
$lab = New-Item -ItemType Directory -Force build/r03-lab
$w6 = Join-Path $env:TEMP ('loopers-w6-' + [Guid]::NewGuid().ToString('N'))
git archive --format=zip --output="$($lab.FullName)/w6.zip" 42d8ed8
Expand-Archive "$($lab.FullName)/w6.zip" -DestinationPath $w6
Push-Location $w6
try {
    ./gradlew.bat :apps:commerce-api:bootJar
    $w6Jar = (Get-ChildItem apps/commerce-api/build/libs/*.jar | Select-Object -First 1).FullName
} finally { Pop-Location }
$apiJar = (Get-ChildItem apps/commerce-api/build/libs/*.jar | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
$streamerJar = (Get-ChildItem apps/commerce-streamer/build/libs/*.jar | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
$pgJar = (Get-ChildItem apps/test-pg/build/libs/*.jar | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
& "$root/docker/payment-lab/Test-OrderDeliveryRecovery.ps1" -W6Jar $w6Jar -CommerceJar $apiJar -StreamerJar $streamerJar -PgJar $pgJar
```

스크립트는 고유 MySQL·Kafka·토픽·consumer group과 테스트 PG를 시작한다. A·B·C는 모두 기존 주문 HTTP API와 실제 PG로 확정한다.
실험은 자동 callback을 생략하고 PG의 거래가 SUCCESS가 된 뒤 기존 주문 대사 API로 확정 경계를 통과한다.
A는 W6 커밋 뒤 직접 발행 메서드 진입에서 정지시키고 해당 API JVM을 종료한다. B는 같은 DB·Kafka에서 정상 발행하고 소비한다.
이후 같은 DB에 `011`을 적용해 A만 복구하고 명령을 다시 실행한다. PG 거래 식별자, HTTP 요청 수, 확정 시각, 재고, B의 구매 활동을 전후 대조한다.
B가 그대로임을 확인한 다음 같은 실험 안에서 B를 명시적으로 재발행한다. Streamer가 동일 사건을 다시 받아도 기존 활동을 유지하는지 확인한다.
C는 Kafka 수신 확인 뒤 Outbox 상태 저장 메서드 진입에서 API JVM을 종료한다. 다시 시작한 Relay가 저장된 행에서 재발행하면 실제 broker record와 Streamer 결과를 확인한다.
중단 지점은 실험용 디버거가 제어한다. 모든 검사 뒤 이 실행에서 만든 프로세스와 컨테이너를 정리하며 `build/r03-lab/<실행ID>`의 로그와 `evidence.json`을 남긴다.

## 운영에서 확인할 상태

| 관찰 | 확인하는 사실 |
|---|---|
| `PENDING`과 예약 시각 | Relay가 아직 완료 상태를 저장하지 않았다. 마지막 오류와 시도 횟수로 다음 시도를 판단한다. |
| `PUBLISHED`와 broker 위치 | 브로커가 메시지를 받아 저장 상태가 커밋됐다. |
| Inbox 완료와 구매 활동 | Streamer가 이벤트를 처리하고 같은 DB 트랜잭션에서 품목별 활동을 커밋했다. |
| consumer group lag와 DB 실패 기록 | 브로커의 끝 위치와 그룹 offset 간 차이, 실패한 원본 record 위치와 원인을 확인한다. |

```sql
SELECT event_id, order_id, status, attempts, next_attempt_at,
       published_topic, published_partition, published_offset, last_error
FROM order_event_outbox WHERE event_id = '확인한 UUID';

SELECT order_id, event_id, processed_at
FROM order_confirmation_inbox WHERE event_id = '확인한 UUID';

SELECT product_id, quantity, confirmed_at
FROM product_purchase_activities WHERE order_id = 42 ORDER BY product_id;

SELECT consumer_group, source_topic, source_partition, source_offset, event_id, reason, recorded_at
FROM order_event_failures ORDER BY recorded_at DESC LIMIT 20;
```

토픽과 그룹의 실제 이름으로 Kafka의 `kafka-consumer-groups.sh --describe --group`를 실행해 파티션별 offset과 lag를 확인한다.
Outbox 조회, 단건 복구와 BLOCKED 재개 명령은 [결제 실험 환경](../../../docker/payment-lab/README.md)을 따른다.

미발행 Outbox와 격리 기록은 해결을 확인할 때까지 보존한다. Kafka retention은 소비자가 장애를 복구해 다시 읽는 데 필요한 기간을 포함해야 한다.
Inbox는 Kafka 재전달과 운영자가 같은 UUID로 재발행할 수 있는 기간 모두에 걸쳐 유지한다. 구매 활동은 Inbox와 외래 키로 연결되므로 삭제 정책도 함께 검토한다.
이미 발행된 Outbox의 정리 시점은 브로커 보존, 구매 활동 확인과 운영 재발행 가능 기간을 함께 기준으로 삼는다.
