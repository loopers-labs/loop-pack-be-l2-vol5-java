package com.loopers.order.application;

import com.loopers.CommerceApiApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

public final class PaymentRecoveryProcessMain {

    private PaymentRecoveryProcessMain() {
    }

    public static void main(String[] args) {
        String mode = args[0];
        SpringApplication application = new SpringApplication(CommerceApiApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        String[] springArgs = {
            "--spring.profiles.active=test",
            "--spring.jpa.hibernate.ddl-auto=none",
            "--datasource.mysql-jpa.main.jdbc-url=" + args[1],
            "--datasource.mysql-jpa.main.username=" + args[2],
            "--datasource.mysql-jpa.main.password=" + args[3],
            "--payment.toss.base-url=" + args[4],
            "--payment.toss.secret-key=" + args[5]
        };
        ConfigurableApplicationContext context = application.run(springArgs);

        if ("approve-and-halt".equals(mode)) {
            var plan = context.getBean(OrderPaymentTransactionService.class).prepare(
                Long.valueOf(args[6]), Long.valueOf(args[7]), args[8], args[9]);
            context.getBean(com.loopers.order.domain.PaymentGateway.class).approve(plan.request());
            Runtime.getRuntime().halt(73);
        }

        context.close();
    }
}
