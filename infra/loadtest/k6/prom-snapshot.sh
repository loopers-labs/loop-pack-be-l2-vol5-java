#!/usr/bin/env bash
# 모니터 서버에서 실행한다 (run-issue-spike.sh 가 ssh 로 넘긴다). 직전 WINDOW 동안의 최댓값·증가량을 뽑는다.
WINDOW=${WINDOW:-2m}
q() { # 제목 PromQL
  printf '%s\n' "$1"
  curl -s -G localhost:9090/api/v1/query --data-urlencode "query=$2" |
    jq -r '.data.result | sort_by(.metric.host // "") | .[] | "  \(.metric.host // .metric.outcome // "-")\t\(.value[1] | tonumber | . * 100 | round / 100)"'
}
q "tomcat 사용 중 스레드 최대"      "max by (host) (max_over_time(tomcat_threads_busy_threads[$WINDOW]))"
q "hikari active 최대"             "max by (host) (max_over_time(hikaricp_connections_active[$WINDOW]))"
q "hikari pending(연결 대기) 최대"  "max by (host) (max_over_time(hikaricp_connections_pending[$WINDOW]))"
q "CPU 사용률 최대(%)"              "max by (host) (max_over_time((100 * (1 - avg by (host) (rate(node_cpu_seconds_total{mode=\"idle\"}[3s]))))[$WINDOW:1s]))"
q "메모리 사용률 최대(%)"           "max by (host) (max_over_time((100 * (1 - node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes))[$WINDOW:1s]))"
q "JVM 힙 사용 최대(MB)"            "max by (host) (max_over_time(sum by (host) (jvm_memory_used_bytes{area=\"heap\"})[$WINDOW:1s])) / 1024 / 1024"
q "MySQL 실행 중 스레드 최대"       "max_over_time(mysql_global_status_threads_running[$WINDOW])"
q "MySQL 연결 최대"                 "max_over_time(mysql_global_status_threads_connected[$WINDOW])"
q "InnoDB 행 잠금 대기 횟수"        "increase(mysql_global_status_innodb_row_lock_waits[$WINDOW])"
q "InnoDB 행 잠금 대기 시간 합(ms)" "increase(mysql_global_status_innodb_row_lock_time[$WINDOW])"
q "InnoDB 행 잠금 대기 최대(ms)"    "max_over_time(mysql_global_status_innodb_row_lock_time_max[$WINDOW])"
