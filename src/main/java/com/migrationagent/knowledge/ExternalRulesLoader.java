package com.migrationagent.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.migrationagent.knowledge.CompatibilityMatrix.LibraryCompat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Loads migration rules and compatibility data from external JSON files.
 *
 * <p>This solves the hardcoding problem: users can add their own rules for
 * internal libraries, proprietary APIs, or any custom migration patterns
 * without modifying source code.</p>
 *
 * <h3>File locations (checked in order):</h3>
 * <ol>
 *   <li>{@code .migration-agent/rules.json} in the project root</li>
 *   <li>{@code .migration-agent/dependencies.json} in the project root</li>
 *   <li>{@code ~/.migration-agent/rules.json} in user home</li>
 *   <li>{@code ~/.migration-agent/dependencies.json} in user home</li>
 * </ol>
 *
 * <h3>rules.json format:</h3>
 * <pre>
 * {
 *   "rules": [
 *     {
 *       "id": "CUSTOM_LEGACY_API",
 *       "type": "REMOVED",
 *       "description": "com.internal.legacy.Encoder removed in JDK 11",
 *       "importPattern": "com.internal.legacy",
 *       "apiPattern": "Encoder",
 *       "replacementImport": "com.internal.modern.Encoder",
 *       "replacement": "Use com.internal.modern.Encoder instead",
 *       "removedInVersion": 11,
 *       "autoFixable": true
 *     }
 *   ]
 * }
 * </pre>
 *
 * <h3>dependencies.json format:</h3>
 * <pre>
 * {
 *   "dependencies": [
 *     {
 *       "groupId": "com.internal",
 *       "artifactId": "internal-lib",
 *       "minJdkVersion": 8,
 *       "versionForJdk8": "1.0.0",
 *       "versionForJdk11": "2.0.0",
 *       "versionForJdk17": "3.0.0",
 *       "versionForJdk21": "3.1.0",
 *       "tightlyCoupled": false
 *     }
 *   ]
 * }
 * </pre>
 */
public class ExternalRulesLoader {

    private static final Logger log = LoggerFactory.getLogger(ExternalRulesLoader.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String CONFIG_DIR = ".migration-agent";

    /**
     * Load external deprecation rules from project and user-level config files.
     *
     * @param projectPath the project root directory
     * @return list of custom rules (empty if none found)
     */
    public static List<DeprecationRule> loadExternalRules(Path projectPath) {
        List<DeprecationRule> rules = new ArrayList<>();

        // Check project-level config
        Path projectRules = projectPath.resolve(CONFIG_DIR).resolve("rules.json");
        if (Files.exists(projectRules)) {
            rules.addAll(loadRulesFromFile(projectRules));
            log.info("Loaded {} custom rules from {}", rules.size(), projectRules);
        }

        // Check user-level config
        Path userRules = Path.of(System.getProperty("user.home"), CONFIG_DIR, "rules.json");
        if (Files.exists(userRules)) {
            List<DeprecationRule> userRulesList = loadRulesFromFile(userRules);
            rules.addAll(userRulesList);
            log.info("Loaded {} user rules from {}", userRulesList.size(), userRules);
        }

        return rules;
    }

    /**
     * Load external dependency compatibility entries.
     *
     * @param projectPath the project root directory
     * @return list of custom compatibility entries (empty if none found)
     */
    public static List<LibraryCompat> loadExternalDependencies(Path projectPath) {
        List<LibraryCompat> deps = new ArrayList<>();

        Path projectDeps = projectPath.resolve(CONFIG_DIR).resolve("dependencies.json");
        if (Files.exists(projectDeps)) {
            deps.addAll(loadDepsFromFile(projectDeps));
            log.info("Loaded {} custom dependency entries from {}", deps.size(), projectDeps);
        }

        Path userDeps = Path.of(System.getProperty("user.home"), CONFIG_DIR, "dependencies.json");
        if (Files.exists(userDeps)) {
            List<LibraryCompat> userDeps2 = loadDepsFromFile(userDeps);
            deps.addAll(userDeps2);
            log.info("Loaded {} user dependency entries from {}", userDeps2.size(), userDeps);
        }

        return deps;
    }

    /**
     * Generate a template rules.json file at the given path.
     */
    public static void generateTemplateRulesFile(Path projectPath) throws IOException {
        Path configDir = projectPath.resolve(CONFIG_DIR);
        Files.createDirectories(configDir);

        String template = """
                {
                  "_comment": "Add your custom migration rules here. These are checked alongside the built-in rules.",
                  "rules": [
                    {
                      "id": "EXAMPLE_CUSTOM_RULE",
                      "type": "REMOVED",
                      "description": "com.example.legacy.OldApi removed — use com.example.modern.NewApi",
                      "importPattern": "com.example.legacy",
                      "apiPattern": "OldApi",
                      "replacementImport": "com.example.modern.NewApi",
                      "replacement": "Use com.example.modern.NewApi instead",
                      "removedInVersion": 11,
                      "autoFixable": true
                    }
                  ]
                }
                """;
        Files.writeString(configDir.resolve("rules.json"), template);

        String depTemplate = """
                {
                  "_comment": "Add your custom dependency compatibility data here.",
                  "dependencies": [
                    {
                      "groupId": "com.example",
                      "artifactId": "example-lib",
                      "minJdkVersion": 8,
                      "versionForJdk8": "1.0.0",
                      "versionForJdk11": "2.0.0",
                      "versionForJdk17": "3.0.0",
                      "versionForJdk21": "3.0.0",
                      "tightlyCoupled": false
                    }
                  ]
                }
                """;
        Files.writeString(configDir.resolve("dependencies.json"), depTemplate);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Internal parsers
    // ═══════════════════════════════════════════════════════════════════

    private static List<DeprecationRule> loadRulesFromFile(Path file) {
        List<DeprecationRule> rules = new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(file.toFile());
            JsonNode rulesArray = root.path("rules");

            if (rulesArray.isArray()) {
                for (JsonNode node : rulesArray) {
                    DeprecationRule rule = new DeprecationRule(
                            node.path("id").asText("CUSTOM_" + rules.size()),
                            parseRuleType(node.path("type").asText("REMOVED")),
                            node.path("description").asText(""),
                            node.path("importPattern").asText(""),
                            node.path("apiPattern").asText(""),
                            node.path("replacementImport").asText(null),
                            node.path("replacement").asText(""),
                            node.path("removedInVersion").asInt(0),
                            node.path("deprecatedInVersion").asInt(0),
                            node.path("autoFixable").asBoolean(false)
                    );
                    rules.add(rule);
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse rules file: {}", file, e);
        }
        return rules;
    }

    private static List<LibraryCompat> loadDepsFromFile(Path file) {
        List<LibraryCompat> deps = new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(file.toFile());
            JsonNode depsArray = root.path("dependencies");

            if (depsArray.isArray()) {
                for (JsonNode node : depsArray) {
                    LibraryCompat compat = new LibraryCompat(
                            node.path("groupId").asText(),
                            node.path("artifactId").asText(),
                            node.path("minJdkVersion").asInt(8),
                            node.path("versionForJdk8").asText(null),
                            node.path("versionForJdk11").asText(null),
                            node.path("versionForJdk17").asText(null),
                            node.path("versionForJdk21").asText(null),
                            node.path("tightlyCoupled").asBoolean(false)
                    );
                    deps.add(compat);
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse dependencies file: {}", file, e);
        }
        return deps;
    }

    private static DeprecationRule.RuleType parseRuleType(String type) {
        try {
            return DeprecationRule.RuleType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            return DeprecationRule.RuleType.REMOVED;
        }
    }
}
