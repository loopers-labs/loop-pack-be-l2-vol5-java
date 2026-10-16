package com.loopers.infrastructure.initializer.mall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ProductSeedRunnerTest {
    private final JdbcProductSeedLoader loader = mock(JdbcProductSeedLoader.class);
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(ProductSeedRunner.class)
        .withInitializer(context -> context.getBeanFactory().registerSingleton("seedLoader", loader));

    @DisplayName("seed 프로필이 없으면 명시적으로 켜도 실행기를 등록하지 않는다")
    @Test
    void doesNotRegisterOutsideSeedProfile() {
        // arrange, act, assert
        contextRunner.withPropertyValues("benchmark.seed.enabled=true")
            .run(context -> assertThat(context).doesNotHaveBean(ProductSeedRunner.class));
    }

    @DisplayName("seed 프로필만 켜고 enabled를 생략하면 실행기를 등록하지 않는다")
    @Test
    void doesNotRegisterUnlessExplicitlyEnabled() {
        // arrange, act, assert
        contextRunner.withInitializer(context -> context.getEnvironment().setActiveProfiles("benchmark-seed"))
            .run(context -> assertThat(context).doesNotHaveBean(ProductSeedRunner.class));
    }

    @DisplayName("seed 프로필과 enabled가 모두 맞을 때 배치 적재를 한 번 호출한다")
    @Test
    void delegatesOnlyWhenProfileAndFlagMatch() {
        // arrange
        contextRunner.withInitializer(context -> context.getEnvironment().setActiveProfiles("benchmark-seed"))
            .withPropertyValues("benchmark.seed.enabled=true")
            .run(context -> {
                assertThat(context).hasSingleBean(ProductSeedRunner.class);

                // act
                context.getBean(ProductSeedRunner.class).run(new DefaultApplicationArguments());

                // assert
                verify(loader).load();
            });
    }
}
