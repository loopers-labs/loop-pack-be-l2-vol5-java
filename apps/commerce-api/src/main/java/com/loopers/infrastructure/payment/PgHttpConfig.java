package com.loopers.infrastructure.payment;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * PG 전용 HTTP client 조립(설계 2.8·2.9). 연결 풀은 다른 외부 연동과 공유하지 않고 생성·조회가 함께 쓴다.
 *
 * <p>자동 retry·redirect 를 끄고 재실행 exec interceptor·RestClient 재시도 interceptor 를 두지 않는다. 생성 POST 의
 * 최초 풀 획득 timeout 을 미발송 증거로 쓰는 조건(2.8 POOL_ACQUIRE_TIMEOUT)이 이 구성에 기대므로 바꾸면 그 판정도 다시 봐야 한다.
 * Boot 의 RestClient.Builder 대신 정적 builder 를 써서 다른 customizer 가 끼어들지 않게 한다.
 * request factory 는 bean 으로 등록하지 않으므로 client 와 전용 connection manager 는 client bean 종료 때 한 번만 닫힌다.
 */
@Configuration
public class PgHttpConfig {

    static final String PROVIDER_USER_HEADER = "X-USER-ID";

    @Bean(destroyMethod = "close")
    public CloseableHttpClient pgHttpClient(PgHttpProperties properties) {
        return HttpClients.custom()
            .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(properties.maxConnections())
                .setMaxConnPerRoute(properties.maxConnections())
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                    .setConnectTimeout(Timeout.of(properties.connectTimeout()))
                    .setSocketTimeout(Timeout.of(properties.readTimeout()))
                    .build())
                .build())
            .setDefaultRequestConfig(RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.of(properties.poolAcquireTimeout()))
                .setResponseTimeout(Timeout.of(properties.readTimeout()))
                .setRedirectsEnabled(false)
                .build())
            .disableAutomaticRetries()
            .disableRedirectHandling()
            .build();
    }

    @Bean
    public RestClient pgRestClient(@Qualifier("pgHttpClient") CloseableHttpClient pgHttpClient,
                                   PgHttpProperties properties) {
        return RestClient.builder()
            .requestFactory(new HttpComponentsClientHttpRequestFactory(pgHttpClient))
            .baseUrl(properties.baseUrl().toString())
            .defaultHeader(PROVIDER_USER_HEADER, properties.accountId())
            .build();
    }
}
