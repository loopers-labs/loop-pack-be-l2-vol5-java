# 차 주문 장애가 물 주문에도 영향을 줬을까?

같은 상품 탐색 부하에서 **장애 없는 대조 실행**과 **차 세 주문의 승인 응답을 잃는 실행**을 비교한다. 관찰할 값은 각 구간에 접수된 **물 주문의 30초 내 결제 확정률**이다. [실측 결과와 Grafana 화면](evidence/2026-11-27-catalog/README.md)

## 어떤 트래픽을 보낼까

| 사용자 행동 | 두 실행에서 고정한 조건 |
|---|---|
| 일반 쇼핑 | 600개 상품·40개 브랜드·14개 카테고리. 120초 동안 탐색 사용자를 1,000명까지 늘리고 세 구간 내내 유지한다. 각 사용자는 1~3초 간격으로 전체·브랜드 목록, 상세, 재방문을 섞는다. 상세·재방문 성공 후 1.6% 확률로 구매를 시도하며 한 주문에 여러 품목도 담는다. |
| 비교용 물 주문 | 전용 계정으로 물 1개씩, 초당 1건을 목표로 보낸다. 일반 쇼핑과 별도로 주문번호·결제 시도 키를 기록한다. |
| 장애 대상 주문 | 별도 계정으로 커피·차 20건을 보낸다. 장애 실행에서만 마지막 차 3건의 PG 승인 생성 응답과 콜백을 유실시킨다. 실제 주문번호는 실행마다 달라진다. |

PG는 결제 대행사다. 이 실험에서는 독립된 테스트 PG와 DB를 실행별로 사용하고, 각 실행 안에서는 정상부터 복구까지 같은 DB·PG를 유지한다. 상품 선호도와 구매 확률은 실험 설정이다. [JMeter 시나리오](control/jmeter/control.jmx) · [상품 구성](control/stack/catalog/catalog.csv)

## 로컬에서 재현하기

Windows PowerShell에서 저장소 루트를 연다. Docker Compose, Git, 동작하는 Python 3, JDK 21, 저장소 루트의 `apache-jmeter-5.6.3`이 필요하다. JMeter 실행기가 기본으로 여는 Java는 `C:\Program Files\Java\jdk-21.0.10\bin\java.exe`다. 아래 `$python`에는 이 환경에서 실행되는 Python을 지정한다. 기존 측정 원장이 있는 DB에서는 제어기가 중단되므로 두 프로젝트의 볼륨이 비어 있는 실습 환경을 사용한다. [스택 시작 스크립트](control/stack/catalog/Start-CatalogStack.ps1) · [JMeter 실행기](control/jmeter/Start-ControlJMeter.ps1)

**1. 대조 실행.** 먼저 앱과 모니터링을 시작한다.

```powershell
$python = 'python'
$lab = '.\docs\week10\control'
.\gradlew.bat :apps:commerce-api:bootJar :apps:test-pg:bootJar
& "$lab\stack\catalog\Start-CatalogStack.ps1"
docker compose -p week10-catalog-monitor -f "$lab\monitor\catalog\compose.yaml" up -d
```

[대조 Grafana](http://127.0.0.1:33311)에서 **Prometheus 수집 상태**가 1인지 확인한 뒤 다음 명령을 실행한다. 제어기가 만든 `build/week10/catalog-control-…` 경로를 기록한다.

```powershell
& $python "$lab\run_control.py"
```

**2. 장애 실행.** 대조 실행이 끝나면 같은 셸에서 장애용 앱과 모니터링을 시작한다.

```powershell
& "$lab\stack\catalog\Start-CatalogStack.ps1" -Incident
& "$lab\monitor\catalog\Start-IncidentMonitor.ps1"
```

[장애 Grafana](http://127.0.0.1:33312)의 수집 상태가 1이면 실행한다. 제어기는 지정한 차 주문 세 건의 타임아웃 로그·DB의 `UNKNOWN`·PG 승인 거래를 먼저 저장한 뒤 검증한 주문의 기존 거래를 반영한다. 원본은 두 실행 모두 `samples.jtl`, `water-orders.tsv`, `telemetry.csv`, `timeline.json`, `result.json`에 남는다. [단계 제어기](control/run_control.py) · [장애 증거 저장과 복구 조건](control/incident.py)

```powershell
& $python "$lab\run_control.py" --incident
```

**3. 시간대를 맞춰 비교한다.** 두 실행이 출력한 경로를 차례로 입력한다. 장애 실행 폴더에 `matched-control.json`이 생긴다. 새 화면이 필요하면 Node.js·Playwright·Edge가 있는 환경에서 Grafana 대시보드 URL(`--url`), 시작·끝 시각(`--from`, `--to`), 새 출력 폴더(`--out`)를 캡처 스크립트에 전달한다. [시간대 비교기](control/compare_runs.py) · [Grafana 캡처 스크립트](control/monitor/catalog/capture-grafana.cjs)

```powershell
$controlRun = Read-Host '이번 대조 실행 폴더'
$incidentRun = Read-Host '이번 장애 실행 폴더'
& $python "$lab\compare_runs.py" $controlRun $incidentRun
```

## 30초 내 확정률 계산

**확정률(%) = 구간에 접수되어 30초 안에 확정된 고유 물 주문 수 ÷ 구간에 접수된 전체 고유 물 주문 수 × 100**

- **구간을 맞춘다.** 정상 10건 확정, 차 3건 미확인, 해당 차 주문 복구를 기준으로 각 1분을 관찰한다. 제어기는 사용자 1,000명과 처리량이 안정되고 상태 변화 이후의 성공 집계와 수집 표본이 들어올 때까지 기다린다. 대조 실행은 더 오래 유지하고, 정상 시작점을 기준으로 장애 실행의 각 구간과 동일한 경과 시각의 1분을 잘라낸다. [15초 DB 집계와 5초 수집](adr-0002-payment-metric-refresh.md)
- **접수 시각으로 주문을 배정한다.** 서버의 `payment_attempts.created_at`이 구간의 시작 이상, 끝 미만인 주문이 분모다. 같은 주문의 `PAID` 기록 시각이 접수 후 0~30초 이내면 분자에 포함한다. 구간 종료 2초 전 접수되고 종료 4초 후 확정된 주문은 그 구간의 6초 성공이다. 지연·미확인 주문도 분모에 남긴다. [접수·확정 원장 집계 SQL](control/monitor/schema.sql)
- **측정 가능 여부를 먼저 본다.** 비교기는 주문·시도 키 중복과 원장 불일치를 검사한다. 분모가 비거나, 전체 구간에 1,000명 유지가 확인되지 않거나, 사용자 표본 간격이 8초를 넘거나, 종료 후 30초를 관찰하지 못하면 그 구간은 ‘측정 불가’다. 이 30초는 실험 기준이다.
