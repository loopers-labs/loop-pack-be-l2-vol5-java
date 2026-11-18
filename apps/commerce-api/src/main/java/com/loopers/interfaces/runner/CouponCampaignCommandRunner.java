package com.loopers.interfaces.runner;

import com.loopers.application.coupon.facade.CouponCampaignOperations;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 로컬에서 실행 인자로 캠페인 운영 동작을 한 번 부르고 끝냄. HTTP 로 노출하지 않음 (8주차 D-166, D-167).
 * 예: --server.port=0 --management.server.port=0 --coupon-campaign.command=open --coupon-campaign.campaign-id=1
 */
@Slf4j
@Profile("local")
@ConditionalOnProperty(name = "coupon-campaign.command")
@Component
public class CouponCampaignCommandRunner implements ApplicationRunner {

    static final int SUCCESS = 0;
    static final int USAGE = 2;

    private final CouponCampaignOperations operations;
    private final Environment environment;
    private final ConfigurableApplicationContext context;

    public CouponCampaignCommandRunner(CouponCampaignOperations operations, Environment environment, ConfigurableApplicationContext context) {
        this.operations = operations;
        this.environment = environment;
        this.context = context;
    }

    /** producer · 스케줄러 스레드가 남아 프로세스가 끝나지 않으므로 직접 종료함 */
    @Override
    public void run(ApplicationArguments args) {
        int exitCode = execute(environment.getProperty("coupon-campaign.command"));
        System.exit(SpringApplication.exit(context, () -> exitCode));
    }

    int execute(String command) {
        return switch (command) {
            case "open" -> open();
            case "close" -> close();
            case "create-users" -> createUsers();
            default -> usage("알 수 없는 명령: " + command);
        };
    }

    private int open() {
        Long campaignId = environment.getProperty("coupon-campaign.campaign-id", Long.class);
        if (campaignId == null) {
            return usage("open 에는 --coupon-campaign.campaign-id 가 필요함");
        }
        int reserveLimit = operations.open(campaignId);
        log.info("쿠폰 캠페인을 열었습니다. campaignId={} reserveLimit={}", campaignId, reserveLimit);
        return SUCCESS;
    }

    private int close() {
        Long campaignId = environment.getProperty("coupon-campaign.campaign-id", Long.class);
        if (campaignId == null) {
            return usage("close 에는 --coupon-campaign.campaign-id 가 필요함");
        }
        operations.close(campaignId);
        log.info("쿠폰 캠페인을 닫았습니다. campaignId={}", campaignId);
        return SUCCESS;
    }

    private int createUsers() {
        Integer count = environment.getProperty("coupon-campaign.user-count", Integer.class);
        if (count == null || count < 1) {
            return usage("create-users 에는 1 이상의 --coupon-campaign.user-count 가 필요함");
        }
        operations.createUsers(count);
        log.info("실험용 사용자를 만들었습니다. count={}", count);
        return SUCCESS;
    }

    private int usage(String message) {
        log.error("{}. 명령: open | close --coupon-campaign.campaign-id=... | create-users --coupon-campaign.user-count=...", message);
        return USAGE;
    }
}
