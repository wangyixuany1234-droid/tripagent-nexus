package com.tripagent.nexus.infrastructure.config;

import java.net.http.HttpClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class MapTileClientConfiguration {

  @Bean
  @Qualifier("mapTileRestClient") RestClient mapTileRestClient(RestClient.Builder builder, MapTileProperties properties) {
    HttpClient httpClient =
        HttpClient.newBuilder().connectTimeout(properties.getConnectTimeout()).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(properties.getReadTimeout());

    return builder
        .baseUrl(properties.getTileBaseUrl().toString())
        .requestFactory(requestFactory)
        .defaultHeader(HttpHeaders.ACCEPT, MediaType.IMAGE_PNG_VALUE)
        .defaultHeader(HttpHeaders.USER_AGENT, properties.getUserAgent())
        .build();
  }
}
