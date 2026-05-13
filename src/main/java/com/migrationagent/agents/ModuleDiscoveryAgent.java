package com.migrationagent.agents;

import com.migrationagent.core.AgentResult;
import com.migrationagent.core.MigrationContext;
import com.migrationagent.model.ModuleInfo;
import com.migrationagent.util.ConsoleUI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Discovers all modules in a project workspace. Supports:
 * <ul>
 *   <li><b>Maven multi-module</b>: Parses parent POM {@code <modules>} section</li>
 *   <li><b>Gradle multi-project</b>: Parses settings.gradle {@code include} directives</li>
 *   <li><b>Multi-application workspace</b>: Scans for sibling directories
 *       that each contain their own build file (pom.xml / build.gradle)</li>
 * </ul>
 *
 * <p>Also resolves inter-module dependencies so the orchestrator can migrate
 * modules in the correct topological order (dependencies first).</p>
 */
public class ModuleDiscoveryAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(ModuleDiscoveryAgent.class);

    @Override
    public String getName() { return "Module Discovery"; }

    @Override
    public AgentResult execute(MigrationContext context) {
        long start = System.currentTimeMillis();
        ConsoleUI.agentStart(getName());

        try {
            Path rootPath = context.getProjectPath();
            List<ModuleInfo> modules = new ArrayList<>();

            // ── Strategy 1: Maven multi-module (parent POM with <modules>) ──
            Path rootPom = rootPath.resolve("pom.xml");
            if (Files.exists(rootPom)) {
                String pomContent = Files.readString(rootPom);
                List<String> declaredModules = parseMavenModules(pomContent);

                if (!declaredModules.isEmpty()) {
                    ConsoleUI.progress("Detected Maven multi-module project with " +
                            declaredModules.size() + " modules");

                    // Add parent module
                    boolean parentHasSrc = Files.exists(rootPath.resolve("src"));
                    ModuleInfo parent = new ModuleInfo("parent", rootPath, rootPom, true);
                    declaredModules.forEach(parent::addChildModule);
                    modules.add(parent);

                    // Add child modules
                    for (String moduleName : declaredModules) {
                        Path modulePath = rootPath.resolve(moduleName);
                        Path modulePom = modulePath.resolve("pom.xml");

                        if (Files.exists(modulePath) && Files.exists(modulePom)) {
                            ModuleInfo child = new ModuleInfo(moduleName, modulePath, modulePom, false);

                            // Parse child's inter-module dependencies
                            String childPomContent = Files.readString(modulePom);
                            resolveInterModuleDeps(child, childPomContent, declaredModules, pomContent);

                            modules.add(child);
                            ConsoleUI.step("Found module: " + moduleName +
                                    (child.getDependsOnModules().isEmpty() ? "" :
                                            " (depends on: " + String.join(", ", child.getDependsOnModules()) + ")"));
                        } else {
                            ConsoleUI.warn("Declared module not found on disk: " + moduleName);
                        }
                    }
                } else {
                    // Single-module Maven project
                    modules.add(new ModuleInfo(rootPath.getFileName().toString(),
                            rootPath, rootPom, false));
                    ConsoleUI.progress("Single-module Maven project detected");
                }
            }

            // ── Strategy 2: Gradle multi-project ──
            Path settingsGradle = rootPath.resolve("settings.gradle");
            Path settingsGradleKts = rootPath.resolve("settings.gradle.kts");
            Path settingsFile = Files.exists(settingsGradle) ? settingsGradle :
                    (Files.exists(settingsGradleKts) ? settingsGradleKts : null);

            if (settingsFile != null && modules.isEmpty()) {
                String settingsContent = Files.readString(settingsFile);
                List<String> gradleModules = parseGradleIncludes(settingsContent);

                if (!gradleModules.isEmpty()) {
                    ConsoleUI.progress("Detected Gradle multi-project with " +
                            gradleModules.size() + " sub-projects");

                    ModuleInfo parent = new ModuleInfo("root", rootPath, settingsFile, true);
                    gradleModules.forEach(parent::addChildModule);
                    modules.add(parent);

                    for (String moduleName : gradleModules) {
                        // Gradle uses ':module-name' → convert to path
                        String pathStr = moduleName.replace(':', '/').replaceFirst("^/", "");
                        Path modulePath = rootPath.resolve(pathStr);
                        Path buildGradle = modulePath.resolve("build.gradle");
                        Path buildGradleKts = modulePath.resolve("build.gradle.kts");
                        Path buildFile = Files.exists(buildGradle) ? buildGradle :
                                (Files.exists(buildGradleKts) ? buildGradleKts : null);

                        if (buildFile != null) {
                            ModuleInfo child = new ModuleInfo(moduleName, modulePath, buildFile, false);
                            // Parse Gradle inter-project dependencies
                            String buildContent = Files.readString(buildFile);
                            resolveGradleInterProjectDeps(child, buildContent, gradleModules);
                            modules.add(child);
                            ConsoleUI.step("Found sub-project: " + moduleName);
                        }
                    }
                }
            }

            // ── Strategy 3: Multi-application workspace ──
            // If no multi-module structure found, scan sibling directories
            if (modules.size() <= 1) {
                List<ModuleInfo> workspaceApps = scanWorkspaceForApplications(rootPath);
                if (workspaceApps.size() > 1) {
                    ConsoleUI.progress("Detected multi-application workspace with " +
                            workspaceApps.size() + " applications");
                    modules.clear();
                    modules.addAll(workspaceApps);
                }
            }

            // ── Apply user module filter ──
            if (context.getConfig().hasModuleFilter()) {
                List<String> filterNames = context.getConfig().getModules();
                int before = modules.size();
                modules = modules.stream()
                        .filter(m -> m.isParent() || filterNames.contains(m.getName()))
                        .collect(Collectors.toList());
                ConsoleUI.info("Module filter applied: " + before + " → " + modules.size() + " modules");
            }

            // ── Sort modules in topological order (deps first) ──
            List<ModuleInfo> ordered = topologicalSort(modules);

            // Store in context
            context.setDiscoveredModules(ordered);

            ConsoleUI.progress("Module migration order:");
            for (int i = 0; i < ordered.size(); i++) {
                ModuleInfo m = ordered.get(i);
                String tag = m.isParent() ? " [PARENT]" : "";
                ConsoleUI.step((i + 1) + ". " + m.getName() + tag);
            }

            ConsoleUI.agentComplete(getName(), true);
            return AgentResult.builder(getName())
                    .status(AgentResult.Status.SUCCESS)
                    .message("Discovered " + ordered.size() + " modules")
                    .issuesFound(0)
                    .durationMs(System.currentTimeMillis() - start)
                    .build();

        } catch (Exception e) {
            log.error("Module discovery failed", e);
            ConsoleUI.error("Module discovery failed: " + e.getMessage());
            return AgentResult.builder(getName())
                    .status(AgentResult.Status.FAILURE)
                    .message("Error: " + e.getMessage())
                    .durationMs(System.currentTimeMillis() - start)
                    .build();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Maven Module Parsing
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Parse {@code <modules><module>...</module></modules>} from a POM.
     */
    private List<String> parseMavenModules(String pomContent) {
        List<String> modules = new ArrayList<>();
        // Match <modules>...<module>name</module>...</modules>
        Pattern modulesBlock = Pattern.compile("<modules>(.*?)</modules>", Pattern.DOTALL);
        Matcher blockMatcher = modulesBlock.matcher(pomContent);

        if (blockMatcher.find()) {
            String block = blockMatcher.group(1);
            Pattern modulePattern = Pattern.compile("<module>\\s*([^<]+)\\s*</module>");
            Matcher moduleMatcher = modulePattern.matcher(block);
            while (moduleMatcher.find()) {
                modules.add(moduleMatcher.group(1).trim());
            }
        }
        return modules;
    }

    /**
     * Determine inter-module dependencies by checking if a child POM
     * depends on artifacts whose artifactId matches another module name.
     */
    private void resolveInterModuleDeps(ModuleInfo module, String childPomContent,
                                         List<String> allModuleNames, String parentPomContent) {
        // Get the parent groupId
        String parentGroupId = extractTag(parentPomContent, "groupId");

        // Find all dependency artifactIds in the child POM
        Pattern depPattern = Pattern.compile(
                "<dependency>\\s*<groupId>([^<]+)</groupId>\\s*<artifactId>([^<]+)</artifactId>",
                Pattern.DOTALL);
        Matcher matcher = depPattern.matcher(childPomContent);

        while (matcher.find()) {
            String depGroupId = matcher.group(1).trim();
            String depArtifactId = matcher.group(2).trim();

            // Check if groupId matches parent and artifactId matches a sibling module
            boolean groupMatches = depGroupId.equals(parentGroupId) ||
                    depGroupId.equals("${project.groupId}") ||
                    depGroupId.equals("${pom.groupId}");

            if (groupMatches && allModuleNames.contains(depArtifactId)) {
                module.addDependsOnModule(depArtifactId);
            }
        }
    }

    private String extractTag(String xml, String tag) {
        Pattern p = Pattern.compile("<" + tag + ">\\s*([^<]+)\\s*</" + tag + ">");
        Matcher m = p.matcher(xml);
        return m.find() ? m.group(1).trim() : "";
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Gradle Module Parsing
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Parse {@code include ':module-a', ':module-b'} from settings.gradle.
     */
    private List<String> parseGradleIncludes(String settingsContent) {
        List<String> modules = new ArrayList<>();
        // Match: include ':module-a', ':module-b'  or  include(':module-a')
        Pattern includePattern = Pattern.compile(
                "include\\s*\\(?\\s*(['\"][^)]+)\\)?",
                Pattern.MULTILINE);
        Matcher blockMatcher = includePattern.matcher(settingsContent);

        while (blockMatcher.find()) {
            String includeStr = blockMatcher.group(1);
            // Extract individual module names
            Pattern namePattern = Pattern.compile("['\"]([^'\"]+)['\"]");
            Matcher nameMatcher = namePattern.matcher(includeStr);
            while (nameMatcher.find()) {
                String name = nameMatcher.group(1).trim();
                // Remove leading colon
                if (name.startsWith(":")) name = name.substring(1);
                modules.add(name);
            }
        }
        return modules;
    }

    /**
     * Resolve Gradle inter-project dependencies like:
     * {@code implementation project(':other-module')}
     */
    private void resolveGradleInterProjectDeps(ModuleInfo module, String buildContent,
                                                 List<String> allModuleNames) {
        Pattern projDepPattern = Pattern.compile(
                "(?:implementation|api|compile|runtimeOnly)\\s*(?:project|projects)\\s*\\(\\s*['\"]:" +
                "([^'\"]+)['\"]\\s*\\)");
        Matcher matcher = projDepPattern.matcher(buildContent);
        while (matcher.find()) {
            String depName = matcher.group(1).trim();
            if (allModuleNames.contains(depName)) {
                module.addDependsOnModule(depName);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Multi-Application Workspace Scanning
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Scan a workspace directory for multiple standalone applications.
     * Each subdirectory that contains its own pom.xml or build.gradle is an "application".
     */
    private List<ModuleInfo> scanWorkspaceForApplications(Path workspacePath) throws IOException {
        List<ModuleInfo> apps = new ArrayList<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(workspacePath)) {
            for (Path entry : stream) {
                if (!Files.isDirectory(entry)) continue;
                String dirName = entry.getFileName().toString();

                // Skip hidden dirs and common non-project dirs
                if (dirName.startsWith(".") || dirName.equals("target") ||
                    dirName.equals("build") || dirName.equals("node_modules") ||
                    dirName.equals("src")) continue;

                Path pomFile = entry.resolve("pom.xml");
                Path buildGradle = entry.resolve("build.gradle");
                Path buildGradleKts = entry.resolve("build.gradle.kts");

                if (Files.exists(pomFile)) {
                    // Check if this is itself a multi-module project
                    String content = Files.readString(pomFile);
                    List<String> subModules = parseMavenModules(content);
                    boolean isParent = !subModules.isEmpty();

                    ModuleInfo app = new ModuleInfo(dirName, entry, pomFile, isParent);
                    subModules.forEach(app::addChildModule);
                    apps.add(app);

                } else if (Files.exists(buildGradle)) {
                    apps.add(new ModuleInfo(dirName, entry, buildGradle, false));

                } else if (Files.exists(buildGradleKts)) {
                    apps.add(new ModuleInfo(dirName, entry, buildGradleKts, false));
                }
            }
        }
        return apps;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Topological Sort for migration order
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Sort modules so that dependencies are migrated before dependents.
     * Parent/aggregator modules come first. Uses Kahn's algorithm.
     */
    private List<ModuleInfo> topologicalSort(List<ModuleInfo> modules) {
        if (modules.size() <= 1) return new ArrayList<>(modules);

        Map<String, ModuleInfo> byName = new LinkedHashMap<>();
        for (ModuleInfo m : modules) byName.put(m.getName(), m);

        // Calculate in-degree
        Map<String, Integer> inDegree = new HashMap<>();
        for (ModuleInfo m : modules) inDegree.put(m.getName(), 0);

        for (ModuleInfo m : modules) {
            for (String dep : m.getDependsOnModules()) {
                if (inDegree.containsKey(dep)) {
                    inDegree.merge(m.getName(), 1, Integer::sum);
                }
            }
        }

        // BFS — start with modules that have no dependencies
        Queue<String> queue = new LinkedList<>();
        // Parents always go first
        for (ModuleInfo m : modules) {
            if (m.isParent()) {
                queue.add(m.getName());
            }
        }
        // Then add zero-indegree non-parents
        for (ModuleInfo m : modules) {
            if (!m.isParent() && inDegree.get(m.getName()) == 0) {
                queue.add(m.getName());
            }
        }

        List<ModuleInfo> sorted = new ArrayList<>();
        Set<String> visited = new HashSet<>();

        while (!queue.isEmpty()) {
            String name = queue.poll();
            if (visited.contains(name)) continue;
            visited.add(name);

            ModuleInfo m = byName.get(name);
            if (m != null) sorted.add(m);

            // Find modules that depend on this one
            for (ModuleInfo other : modules) {
                if (!visited.contains(other.getName()) &&
                    other.getDependsOnModules().contains(name)) {
                    // Decrease in-degree
                    int newDeg = inDegree.merge(other.getName(), -1, Integer::sum);
                    if (newDeg <= 0) {
                        queue.add(other.getName());
                    }
                }
            }
        }

        // Add any remaining modules (circular deps or disconnected)
        for (ModuleInfo m : modules) {
            if (!visited.contains(m.getName())) {
                sorted.add(m);
                log.warn("Module {} may have circular dependencies", m.getName());
            }
        }

        return sorted;
    }
}
