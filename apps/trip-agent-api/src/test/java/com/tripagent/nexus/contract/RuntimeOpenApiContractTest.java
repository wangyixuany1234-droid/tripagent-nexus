package com.tripagent.nexus.contract;

import static io.restassured.RestAssured.given;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.EMBEDDED;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.RefreshMode.BEFORE_EACH_TEST_METHOD;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"springdoc.api-docs.enabled=true", "springdoc.api-docs.version=OPENAPI_3_1"})
@ActiveProfiles({"test", "mock-model"})
@AutoConfigureEmbeddedDatabase(
    provider = EMBEDDED,
    type = POSTGRES,
    refresh = BEFORE_EACH_TEST_METHOD)
class RuntimeOpenApiContractTest {

  @LocalServerPort private int port;

  @Autowired private ObjectMapper objectMapper;

  @Test
  void runningBackendPublishesAnOpenApiCompatibleWithCurrentAndFrozenContracts()
      throws IOException {
    String document =
        given()
            .port(port)
            .accept(MediaType.APPLICATION_JSON_VALUE)
            .when()
            .get("/v3/api-docs")
            .then()
            .statusCode(200)
            .contentType(MediaType.APPLICATION_JSON_VALUE)
            .extract()
            .asString();

    JsonNode runtimeDocument = objectMapper.readTree(document);
    assertThat(runtimeDocument.path("openapi").asText()).startsWith("3.1.");
    writeRuntimeContract(runtimeDocument);

    OpenAPI runtime = parseContents(document, "runtime /v3/api-docs");
    OpenAPI current = parse(findContract("packages/api-contract/openapi.yaml"));
    OpenAPI frozen = parse(findContract("contracts/openapi/tripagent-v1.yaml"));

    assertThat(operations(runtime))
        .as("runtime OpenAPI operations must exactly match the current contract")
        .containsExactlyElementsOf(operations(current));
    assertThat(operations(runtime))
        .as("runtime OpenAPI must retain every frozen v1 operation")
        .containsAll(operations(frozen));
  }

  private static Set<String> operations(OpenAPI openApi) {
    Set<String> operations = new TreeSet<>();
    openApi
        .getPaths()
        .forEach(
            (path, item) ->
                item.readOperationsMap()
                    .keySet()
                    .forEach(
                        method ->
                            operations.add(method.name().toUpperCase(Locale.ROOT) + " " + path)));
    return operations;
  }

  private static OpenAPI parse(Path contract) {
    ParseOptions options = new ParseOptions();
    options.setResolve(true);
    options.setResolveFully(true);
    SwaggerParseResult result =
        new OpenAPIV3Parser().readLocation(contract.toString(), null, options);
    assertThat(result.getMessages()).as("parse messages for %s", contract).isEmpty();
    assertThat(result.getOpenAPI()).as("parsed OpenAPI for %s", contract).isNotNull();
    return result.getOpenAPI();
  }

  private static OpenAPI parseContents(String document, String source) {
    ParseOptions options = new ParseOptions();
    options.setResolve(true);
    options.setResolveFully(true);
    SwaggerParseResult result = new OpenAPIV3Parser().readContents(document, null, options);
    assertThat(result.getMessages()).as("parse messages for %s", source).isEmpty();
    assertThat(result.getOpenAPI()).as("parsed OpenAPI for %s", source).isNotNull();
    return result.getOpenAPI();
  }

  private static Path findContract(String relativePath) {
    Path directory = Path.of("").toAbsolutePath();
    while (directory != null) {
      Path candidate = directory.resolve(relativePath);
      if (Files.exists(candidate)) {
        return candidate;
      }
      directory = directory.getParent();
    }
    throw new IllegalStateException("Could not locate contract: " + relativePath);
  }

  private void writeRuntimeContract(JsonNode runtimeDocument) throws IOException {
    String resultsRoot = System.getenv("QA_RESULTS_ROOT");
    String runId = System.getenv("QA_RUN_ID");
    if (resultsRoot == null || runId == null) {
      return;
    }
    Path output = Path.of(resultsRoot, runId, "json", "spring-runtime-openapi.json");
    Files.createDirectories(output.getParent());
    objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), runtimeDocument);
  }
}
