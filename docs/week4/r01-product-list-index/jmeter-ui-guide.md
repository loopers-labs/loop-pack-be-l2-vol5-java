# JMeter GUI로 실시간 측정 보기

[전체 가이드](jmeter-guide.md) · [GUI 실행기](jmeter/open-jmeter-ui.ps1) · [GUI JMX](jmeter/product-list-ui.jmx)

GUI는 계획만 열며 **초록색 Start 버튼을 눌러야 요청을 보낸다**.
이번에 중단된 CLI 측정 원본은 보존한다. 서버·DB·인덱스·응답 timeout 30초는 바꾸지 않는다.
현재는 승인한 인덱스 2개를 적용해 보조 인덱스 6개 상태다. **변경 전 GUI 창을 닫고 아래 실행기로 새로 열어** 변경 후 결과 폴더를 만든다.
열려 있던 창에서 다시 Start를 누르면 변경 전 JTL에 추가될 수 있으므로 전후를 섞지 않는다.
이후 사용자가 CLI 실행을 위임해 [변경 후 12개 측정](baseline/jmeter-after/README.md)을 완료했다. GUI 결과와 CLI 결과는 합산하지 않는다.

## 1. 화면 열기

변경 후 측정은 이전 창을 닫은 다음 저장소 루트 PowerShell에서 새로 연다.

```powershell
$bench = '.\docs\week4\r01-product-list-index\jmeter'
$gui = & "$bench\open-jmeter-ui.ps1"
```

측정용 API와 Docker는 켜둔다. CLI 측정과 GUI 측정을 동시에 실행하지 않는다.

## 2. 조회·동시 사용자 선택

왼쪽 트리의 최상위 Test Plan을 눌러 **User Defined Variables** 표를 찾는다.

| 변수 | 선택할 값 |
|---|---|
| `scenario` | `latest` 전체 최신순 / `price` 전체 가격순 / `brand7` 브랜드 7 가격순 |
| `threads` | `1` 또는 `10` |
| `warmup` | `20`초 |
| `duration` | `60`초 |
| `rampup` | `5`초, 각 실행 시간에 포함 |

처음 열면 `latest`, `1`이다. 한 번의 Start는 선택한 조합 **한 번**을 실행한다.
3종 조회 × 동시 수 2개 × 반복 2회는 조합을 바꿔 총 12번 실행하는 구성이다.
조합을 바꿀 때는 GUI를 닫고 실행기를 다시 열어 새 결과 폴더를 사용한다.
같은 창에서 Start를 반복하면 기존 JTL에 표본이 추가될 수 있으므로 결과를 섞지 않는다.

## 3. 실시간 표 보기

1. 왼쪽 Measurement 아래의 **Aggregate Report**를 선택한다.
2. 상단 **초록 삼각형 Start** 또는 `Ctrl+R`을 누른다.
3. 먼저 20초 워밍업이 실행된다. 워밍업 동안 본 측정 표가 비어 있는 것은 정상이다.
4. 이어지는 60초 본 측정에서 표가 갱신된다. 응답이 끝나야 표본이 추가되므로 느린 요청은 바로 표시되지 않는다.

| 열 | 읽는 방법 |
|---|---|
| `# Samples` | 완료한 요청 수 |
| `Average` / `Median` | 평균 / p50 응답 시간(ms) |
| `95% Line` / `99% Line` | p95 / p99 응답 시간(ms) |
| `Error %` | 실패 비율. 30초 응답 timeout도 실패에 포함 |
| `Throughput` | 처리량. 표시된 시간 단위를 함께 확인 |

진행 중인 요청 종료를 기다리므로 전체 실행은 80초보다 길어질 수 있다.
GUI는 오류 표본을 표에 포함해 보여주며, 기존 CLI 래퍼처럼 오류율이 0이 아니라는 이유로 창을 닫지 않는다.
실패 표본을 지우거나 성공 요청만으로 평균을 내지 않는다.

## 4. 결과 보관

콘솔에 출력된 `build/benchmark/jmeter-ui/실행시각-…/`에 다음 파일이 저장된다.

- `warmup.jtl`: 워밍업 원본
- `measurement.jtl`: 본 측정 원본
- `jmeter.log`, `session.json`: GUI 로그와 실행 파일 위치

선택한 변수값과 반복 번호를 함께 기록한다. 필요하면 GUI의 **File → Save Test Plan as**로 변경한 JMX를 같은 결과 폴더에 보관한다.
CLI의 자동 HTML 생성과 별개로, GUI의 실시간 표와 JTL을 남기는 흐름이다.
GUI로 전후 비교할 때는 같은 GUI·Listener 설정을 사용하고 이전 CLI 표본과 합치지 않는다.
