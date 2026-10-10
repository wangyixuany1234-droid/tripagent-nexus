package com.tripagent.nexus.contract;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.EMBEDDED;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.RefreshMode.BEFORE_EACH_TEST_METHOD;
import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@SpringBootTest
@ActiveProfiles({"test", "mock-model"})
@AutoConfigureEmbeddedDatabase(
    provider = EMBEDDED,
    type = POSTGRES,
    refresh = BEFORE_EACH_TEST_METHOD)
class ControllerOpenApiParityTest {

  @Autowired
  @Qualifier("requestMappingHandlerMapping") private RequestMappingHandlerMapping mappings;

  @Test
  void controllerOperationsExactlyMatchThePublishedOpenApi() {
    Set<String> controllerOperations = new TreeSet<>();
    mappings
        .getHandlerMethods()
        .forEach(
            (mapping, handler) -> {
              Class<?> controller = handler.getBeanType();
              if (!controller.getPackageName().startsWith("com.tripagent.nexus.controller")
                  || !controller.isAnnotationPresent(RestController.class)) {
                return;
              }
              mapping
                  .getPatternValues()
                  .forEach(
                      path ->
                          mapping
                              .getMethodsCondition()
                              .getMethods()
                              .forEach(
                                  method -> controllerOperations.add(method.name() + " " + path)));
            });

    OpenAPI openApi =
        new OpenAPIV3Parser()
            .readLocation(findContract().toString(), null, new ParseOptions())
            .getOpenAPI();
    Set<String> contractOperations = new TreeSet<>();
    openApi
        .getPaths()
        .forEach(
            (path, item) ->
                item.readOperationsMap()
                    .keySet()
                    .forEach(
                        method ->
                            contractOperations.add(
                                method.name().toUpperCase(Locale.ROOT) + " " + path)));

    assertThat(controllerOperations)
        .as("Spring MVC operations must exactly match packages/api-contract/openapi.yaml")
        .containsExactlyElementsOf(contractOperations);
  }

  private static Path findContract() {
    Path directory = Path.of("").toAbsolutePath();
    while (directory != null) {
      Path candidate = directory.resolve("packages/api-contract/openapi.yaml");
      if (Files.exists(candidate)) {
        return candidate;
      }
      directory = directory.getParent();
    }
    throw new IllegalStateException("Could not locate OpenAPI contract");
  }
}
