package com.tripagent.nexus.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import eu.rekawek.toxiproxy.model.ToxicDirection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SuppressWarnings("resource")
class ToxiproxyTestcontainersIntegrationTest {

  private static final Network NETWORK = Network.newNetwork();

  @Container
  static final GenericContainer<?> UPSTREAM =
      new GenericContainer<>(DockerImageName.parse("nginx:1.27.5-alpine"))
          .withNetwork(NETWORK)
          .withNetworkAliases("synthetic-upstream")
          .withExposedPorts(80);

  @Container
  static final ToxiproxyContainer TOXIPROXY =
      new ToxiproxyContainer(
              DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.12.0")
                  .asCompatibleSubstituteFor("shopify/toxiproxy"))
          .withNetwork(NETWORK);

  @Test
  void downstreamTimeoutIsObservableAndConnectionRecoversAfterToxicRemoval() throws Exception {
    ToxiproxyContainer.ContainerProxy proxy = TOXIPROXY.getProxy(UPSTREAM, 80);
    URI uri = URI.create("http://" + proxy.getContainerIpAddress() + ":" + proxy.getProxyPort());
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    assertThat(send(client, uri).statusCode()).isEqualTo(200);
    var toxic = proxy.toxics().timeout("qa-downstream-timeout", ToxicDirection.DOWNSTREAM, 100);

    assertThatThrownBy(() -> send(client, uri))
        .isInstanceOfAny(java.io.IOException.class, InterruptedException.class);

    toxic.remove();
    assertThat(send(client, uri).statusCode()).isEqualTo(200);
  }

  private static HttpResponse<Void> send(HttpClient client, URI uri) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(500)).GET().build();
    return client.send(request, HttpResponse.BodyHandlers.discarding());
  }
}
