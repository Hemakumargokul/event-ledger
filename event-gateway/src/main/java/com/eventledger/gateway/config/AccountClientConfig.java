package com.eventledger.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class AccountClientConfig {

    /**
     * Built from the auto-configured builder so Micrometer Tracing instruments
     * the client and W3C traceparent propagates (SPEC §9.1). Timeouts per
     * SPEC §8.2 bound each individual attempt; the retry policy sits above.
     */
    @Bean
    RestClient accountServiceRestClient(RestClient.Builder builder,
                                        @Value("${account-service.base-url}") String baseUrl,
                                        @Value("${account-service.connect-timeout}") Duration connectTimeout,
                                        @Value("${account-service.read-timeout}") Duration readTimeout) {
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder.jdk()
                .build(HttpClientSettings.defaults()
                        .withConnectTimeout(connectTimeout)
                        .withReadTimeout(readTimeout));
        return builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
    }
}
