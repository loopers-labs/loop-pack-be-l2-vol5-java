package com.loopers.batch.listener;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.annotation.AfterJob;
import org.springframework.stereotype.Component;

/**
 * job이 끝난 뒤 결과를 알린다 (W9 D-5·D-8). 알림이 실패해도 업무 데이터는 틀어지지 않으므로 listener에 둔다.
 * 실제 발송은 실행기(Jenkins 등)가 종료 코드로 한다. kill되면 이 listener도 돌지 않는다 — 그 경우도 실행기가 종료 코드로 잡는다.
 */
@Slf4j
@Component
public class JobResultAlertListener {

    @AfterJob
    void afterJob(JobExecution jobExecution) {
        String jobName = jobExecution.getJobInstance().getJobName();
        if (jobExecution.getStatus() == BatchStatus.FAILED) {
            log.error("[ALERT] '{}' 실행이 실패했습니다. 원인을 확인한 뒤 같은 명령으로 재시작하세요. [jobExecutionId = {}, parameters = {}, 원인 = {}]",
                jobName, jobExecution.getId(), jobExecution.getJobParameters(), jobExecution.getAllFailureExceptions());
            return;
        }
        log.info("'{}' 실행이 끝났습니다. [jobExecutionId = {}, status = {}, exitStatus = {}]",
            jobName, jobExecution.getId(), jobExecution.getStatus(), jobExecution.getExitStatus());
    }
}
