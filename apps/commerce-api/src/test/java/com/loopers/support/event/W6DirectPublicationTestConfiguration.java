package com.loopers.support.event;

import com.loopers.application.event.DomainEventListener;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** W6 preparation only. Replaces one bean definition instead of enabling a production bypass. */
@TestConfiguration(proxyBeanMethods = false)
public class W6DirectPublicationTestConfiguration {
    @Bean
    static BeanFactoryPostProcessor w6DirectPublicationListener() {
        return beanFactory -> {
            var definition = (AbstractBeanDefinition) beanFactory.getBeanDefinition("domainEventListener");
            if (!DomainEventListener.class.getName().equals(definition.getBeanClassName())) {
                throw new IllegalStateException("W6 fixture must replace the production common event listener");
            }
            definition.setBeanClass(W6DomainEventListener.class);
        };
    }
}
