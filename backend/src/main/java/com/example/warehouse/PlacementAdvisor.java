package com.example.warehouse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Service
class PlacementAdvisor {
  private static final Logger log = LoggerFactory.getLogger(PlacementAdvisor.class);
  private static final int MAX_ATTEMPTS = 3;
  private final ObjectMapper mapper;
  private final RestClient client;
  private final String provider;
  private final String apiKey;
  private final String model;
  private final String openRouterProvider;

  PlacementAdvisor(ObjectMapper mapper,
      @Value("${warehouse.ai-provider:mock}") String provider,
      @Value("${warehouse.openrouter-api-key:}") String apiKey,
      @Value("${warehouse.openrouter-model:openai/gpt-4o-mini}") String model,
      @Value("${warehouse.openrouter-provider:}") String openRouterProvider,
      @Value("${warehouse.openrouter-timeout-seconds:60}") int timeoutSeconds) {
    this.mapper = mapper;
    this.provider = provider;
    this.apiKey = apiKey;
    this.model = model;
    this.openRouterProvider = openRouterProvider.trim();
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(Duration.ofSeconds(Math.min(timeoutSeconds, 15)));
    requestFactory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
    this.client = RestClient.builder().baseUrl("https://openrouter.ai/api/v1").requestFactory(requestFactory).build();
  }

  ApiModels.PlacementPlan propose(List<ApiModels.IncomingLoad> loads, List<ApiModels.CandidateSlot> candidates,
      ApiModels.PlanningMap warehouseMap, String prompt) {
    if ("mock".equalsIgnoreCase(provider)) {
      if (candidates.size() < loads.size()) throw new IllegalArgumentException("Not enough available storage slots");
      return new ApiModels.PlacementPlan(java.util.stream.IntStream.range(0, loads.size())
          .mapToObj(index -> new ApiModels.Placement(loads.get(index).id(), candidates.get(index).id(), "Deterministic nearest eligible slot"))
          .toList());
    }
    if (!"openrouter".equalsIgnoreCase(provider)) throw new IllegalStateException("Unsupported AI provider: " + provider);
    if (apiKey.isBlank()) throw new IllegalStateException("OPENROUTER_API_KEY is required when AI_PROVIDER=openrouter");
    return callOpenRouter(loads, candidates, warehouseMap, prompt);
  }

  private ApiModels.PlacementPlan callOpenRouter(List<ApiModels.IncomingLoad> loads, List<ApiModels.CandidateSlot> candidates,
      ApiModels.PlanningMap warehouseMap, String prompt) {
    Map<String, Object> schema = Map.of(
        "type", "object",
        "additionalProperties", false,
        "properties", Map.of("placements", Map.of("type", "array", "items", Map.of(
            "type", "object", "additionalProperties", false,
            "properties", Map.of("loadId", Map.of("type", "string"), "slotId", Map.of("type", "string"), "reason", Map.of("type", "string")),
            "required", List.of("loadId", "slotId", "reason")))),
        "required", List.of("placements"));
    String snapshot = write(Map.of("incomingLoads", loads, "eligibleSlots", candidates, "warehouseMap", warehouseMap,
        "operatorRequest", prompt == null ? "" : prompt));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", model);
    body.put("messages", List.of(
        Map.of("role", "system", "content", "Choose exactly one unique eligible slot for each load. "
            + "Each eligible slot names the aisle that serves it, and warehouseMap.aisles lists them; when the operator "
            + "names an aisle the eligible slots have already been narrowed to it, so choose only from what you are given. "
            + "Return only a JSON object with a placements array; each placement must contain loadId, slotId, and reason strings. Do not use Markdown."),
        Map.of("role", "user", "content", snapshot)));
    body.put("response_format", Map.of("type", "json_schema", "json_schema", Map.of("name", "warehouse_placement", "strict", true, "schema", schema)));
    Map<String, Object> providerRouting = new LinkedHashMap<>();
    providerRouting.put("require_parameters", openRouterProvider.isBlank());
    if (!openRouterProvider.isBlank()) {
      providerRouting.put("only", List.of(openRouterProvider));
      providerRouting.put("allow_fallbacks", false);
    }
    body.put("provider", providerRouting);
    body.put("temperature", 0);
    body.put("max_completion_tokens", 1200);
    String responseBody = request(body);
    try {
      if (responseBody == null || responseBody.isBlank()) throw new IllegalStateException("OpenRouter returned an empty response");
      JsonNode response = mapper.readTree(responseBody);
      String content = response.at("/choices/0/message/content").asText();
      if (content.isBlank()) throw new IllegalStateException("OpenRouter returned no placement content");
      return mapper.readValue(content, ApiModels.PlacementPlan.class);
    } catch (Exception exception) {
      throw new IllegalStateException("OpenRouter returned an invalid placement plan", exception);
    }
  }

  private String request(Map<String, Object> body) {
    ResourceAccessException lastFailure = null;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      try {
        return client.post().uri("/chat/completions")
            .header("Authorization", "Bearer " + apiKey)
            .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class);
      } catch (ResourceAccessException exception) {
        lastFailure = exception;
        log.warn("OpenRouter transport failed on attempt {}/{} for model {}: {}", attempt, MAX_ATTEMPTS, model,
            exception.getMostSpecificCause().getMessage());
        if (attempt < MAX_ATTEMPTS) pauseBeforeRetry(attempt);
      }
    }
    throw new IllegalStateException("OpenRouter connection closed before a response after " + MAX_ATTEMPTS + " attempts", lastFailure);
  }

  private static void pauseBeforeRetry(int attempt) {
    try {
      Thread.sleep(300L * attempt);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while retrying OpenRouter", exception);
    }
  }

  private String write(Object value) {
    try { return mapper.writeValueAsString(value); } catch (Exception exception) { throw new IllegalStateException(exception); }
  }
}
