package com.tripagent.nexus.infrastructure.config;

import java.net.http.HttpClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class DeveloperTraceConfiguration {

  @Bean
  @Qualifier("jaegerRestClient") RestClient jaegerRestClient(RestClient.Builder builder, DeveloperToolsProperties properties) {
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.getTimeout()).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(properties.getTimeout());
    return builder
        .baseUrl(properties.getJaegerBaseUrl().toString())
        .requestFactory(requestFactory)
        .build();
  }
}
