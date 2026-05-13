package com.migrationagent.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Client to query the public Maven Central Repository for dependency information.
 */
public class MavenCentralClient {

    private static final Logger log = LoggerFactory.getLogger(MavenCentralClient.class);
    private static final String SEARCH_URL = "https://search.maven.org/solrsearch/select?q=g:\"%s\"+AND+a:\"%s\"&rows=1&wt=json";

    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public MavenCentralClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.mapper = new ObjectMapper();
    }

    /**
     * Finds the latest version of a dependency in Maven Central.
     *
     * @param groupId    the group ID
     * @param artifactId the artifact ID
     * @return the latest version, or empty if not found or on error
     */
    public Optional<String> findLatestVersion(String groupId, String artifactId) {
        try {
            String encodedGroupId = URLEncoder.encode(groupId, StandardCharsets.UTF_8);
            String encodedArtifactId = URLEncoder.encode(artifactId, StandardCharsets.UTF_8);
            String url = String.format(SEARCH_URL, encodedGroupId, encodedArtifactId);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.debug("Maven Central returned status {} for {}:{}", response.statusCode(), groupId, artifactId);
                return Optional.empty();
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode docs = root.path("response").path("docs");

            if (docs.isArray() && !docs.isEmpty()) {
                String latestVersion = docs.get(0).path("latestVersion").asText(null);
                if (latestVersion != null && !latestVersion.isBlank()) {
                    return Optional.of(latestVersion);
                }
            }
        } catch (Exception e) {
            log.debug("Failed to query Maven Central for {}:{}", groupId, artifactId, e);
        }

        return Optional.empty();
    }
}
