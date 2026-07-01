package com.ragbrain.rag.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Builds {@link RestClient.Builder}s with connect/read timeouts for the Spring AI
 * model clients. Spring AI's {@code OpenAiApi}/{@code AnthropicApi} builders use a
 * default {@code RestClient} with NO read timeout, so a hung provider would block
 * a request thread (and any DB connection held by the caller) indefinitely. Every
 * AI client in this app is built from one of these timeout-bound builders instead.
 *
 * <p>Timeouts are configurable via {@code ragbrain.rag.ai.connect-timeout-ms} and
 * {@code ragbrain.rag.ai.read-timeout-ms}. The read timeout must comfortably exceed a
 * normal completion latency; a too-small value will turn slow-but-valid answers
 * into failures.
 */
@Component
public class AiHttpClientFactory {

    private final Duration connectTimeout;
    private final Duration readTimeout;

    public AiHttpClientFactory(
            @Value("${ragbrain.rag.ai.connect-timeout-ms:10000}") long connectTimeoutMs,
            @Value("${ragbrain.rag.ai.read-timeout-ms:60000}") long readTimeoutMs) {
        this.connectTimeout = Duration.ofMillis(connectTimeoutMs);
        this.readTimeout = Duration.ofMillis(readTimeoutMs);
    }

    /** A fresh RestClient.Builder backed by a timeout-configured request factory. */
    public RestClient.Builder restClientBuilder() {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(connectTimeout)
                .withReadTimeout(readTimeout);
        ClientHttpRequestFactory factory = ClientHttpRequestFactoryBuilder.detect().build(settings);
        return RestClient.builder().requestFactory(factory);
    }
}
