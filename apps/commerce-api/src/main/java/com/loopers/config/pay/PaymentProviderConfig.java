package com.loopers.config.pay;

import java.net.URI;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class PaymentProviderConfig {
    @Bean(destroyMethod = "close")
    public CloseableHttpClient paymentProviderHttpClient() {
        var manager = PoolingHttpClientConnectionManagerBuilder.create().setDefaultConnectionConfig(
                ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(1)).setSocketTimeout(Timeout.ofSeconds(2)).build())
                .build();
        var request = RequestConfig.custom().setConnectionRequestTimeout(Timeout.ofSeconds(1))
                .setResponseTimeout(Timeout.ofSeconds(2)).build();
        return HttpClients.custom().setConnectionManager(manager).setDefaultRequestConfig(request)
                .disableAutomaticRetries().disableRedirectHandling().build();
    }

    @Bean
    public RestClient paymentProviderRestClient(CloseableHttpClient paymentProviderHttpClient,
                                                @Value("${pg.client.base-url}") String baseUrl) {
        URI uri = localUri(baseUrl);
        if (uri.getQuery() != null || (!uri.getPath().isEmpty() && !uri.getPath().equals("/"))) {
            throw new IllegalArgumentException("PG 기본 주소에는 경로와 query를 지정하지 않습니다.");
        }
        return RestClient.builder().baseUrl(baseUrl)
                .requestFactory(new HttpComponentsClientHttpRequestFactory(paymentProviderHttpClient)).build();
    }

    @Bean
    public URI paymentCallbackUri(@Value("${pg.client.callback-url}") String callbackUrl) {
        return localUri(callbackUrl);
    }

    private URI localUri(String value) {
        URI uri = URI.create(value);
        if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost()) || uri.getPort() < 1 || uri.getPort() > 65535
                || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("PG 주소는 설정한 127.0.0.1 HTTP 주소만 허용합니다.");
        }
        return uri;
    }
}
