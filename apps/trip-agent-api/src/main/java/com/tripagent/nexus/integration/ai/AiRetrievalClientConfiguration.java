package com.tripagent.nexus.integration.ai;

import com.tripagent.nexus.infrastructure.config.AiServiceProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class AiRetrievalClientConfiguration {

  @Bean
  @Qualifier("aiRetrievalRestClient") RestClient aiRetrievalRestClient(RestClient.Builder builder, AiServiceProperties properties) {
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(Duration.ofSeconds(3));

    return builder
        .baseUrl(properties.getBaseUrl().toString())
        .requestFactory(requestFactory)
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getServiceToken())
        .build();
  }

  @Bean
  @Qualifier("aiDocumentRestClient") RestClient aiDocumentRestClient(RestClient.Builder builder, AiServiceProperties properties) {
    HttpClient httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(properties.getDocumentTimeout());

    return builder
        .baseUrl(properties.getBaseUrl().toString())
        .requestFactory(requestFactory)
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getServiceToken())
        .build();
  }
}
