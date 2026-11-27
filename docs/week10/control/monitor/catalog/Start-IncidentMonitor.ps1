$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../../../../..')).Path
$out = Join-Path $repo 'build/week10/incident-monitor'
[void][IO.Directory]::CreateDirectory($out)
$dashboard = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'dashboard.json') -Raw -Encoding UTF8
$dashboard = $dashboard.Replace('장애 대응 시간대 · 대조','장애').Replace('복구 대응 시간대 · 대조','복구')
$board = $dashboard | ConvertFrom-Json
$board.uid = 'w10-catalog-incident'
$board.title = '상품 탐색과 구매 · 차 주문 3건 결제 장애'
foreach ($panel in $board.panels) { $panel.gridPos.y += 8 }
$count = ($board.panels | Where-Object id -eq 14 | ConvertTo-Json -Depth 60) | ConvertFrom-Json
$count.id = 90
$count.title = '현재 미확인 주문'
$count.gridPos = @{ x=0; y=0; w=6; h=8 }
$series = ($board.panels | Where-Object id -eq 11 | ConvertTo-Json -Depth 60) | ConvertFrom-Json
$series.id = 91
$series.title = '미확인 주문 · Prometheus가 5초마다 수집한 실제 값'
$series.description = 'PG 응답 기한이 지나도 결과가 UNKNOWN인 전체 주문 수. 집계 주기는 15초입니다.'
$series.gridPos = @{ x=6; y=0; w=18; h=8 }
$source = @{ type='prometheus'; uid='w10-prom' }
$series.datasource = $source
$series.targets = @(@{ refId='A'; expr='commerce_payment_unknown{job="commerce-control"}'; datasource=$source; range=$true; legendFormat='미확인 주문 수' })
$series.fieldConfig = @{ defaults=@{ unit='short'; min=0; decimals=0; custom=@{ drawStyle='line'; lineInterpolation='stepAfter'; lineWidth=3; fillOpacity=12; spanNulls=$false } }; overrides=@() }
$board.panels = @($count,$series) + @($board.panels | Where-Object id -ne 14)
$path = Join-Path $out 'incident-dashboard.json'
[IO.File]::WriteAllText($path, ($board | ConvertTo-Json -Depth 90), [Text.UTF8Encoding]::new($false))
$env:CATALOG_BOARD = $path.Replace('\','/')
$env:CATALOG_NETWORK = 'week10-catalog-incident_default'
$env:CATALOG_GRAFANA_PORT = '33312'
$env:CATALOG_PROM_PORT = '39092'
docker compose -p week10-catalog-incident-monitor -f (Join-Path $PSScriptRoot 'compose.yaml') up -d
if ($LASTEXITCODE -ne 0) { throw 'Incident monitoring stack startup failed.' }
