package com.migrationagent.knowledge;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Database of known JDK API changes across versions.
 * This is the brain of the migration agent — it knows what changed and how to fix it.
 */
public class JdkApiChangeDatabase {

    private final List<DeprecationRule> rules = new ArrayList<>();

    public JdkApiChangeDatabase() {
        initializeRules();
    }

    public List<DeprecationRule> getAllRules() {
        return Collections.unmodifiableList(rules);
    }

    public List<DeprecationRule> getRulesFor(int sourceVersion, int targetVersion) {
        return rules.stream()
                .filter(r -> r.appliesTo(sourceVersion, targetVersion))
                .collect(Collectors.toList());
    }

    public List<DeprecationRule> getAutoFixableRules(int sourceVersion, int targetVersion) {
        return getRulesFor(sourceVersion, targetVersion).stream()
                .filter(DeprecationRule::isAutoFixable)
                .collect(Collectors.toList());
    }

    private void initializeRules() {
        // ═══════════════════════════════════════════════════════════
        // JDK 6/7 → 8 Changes
        // ═══════════════════════════════════════════════════════════
        addSunMiscRules();
        addJavaxAnnotationRules();

        // ═══════════════════════════════════════════════════════════
        // JDK 8 → 11 Changes (includes JDK 9 module system)
        // ═══════════════════════════════════════════════════════════
        addJaxbRules();
        addJaxWsRules();
        addCorbaRules();
        addNashornRules();
        addJavaFxRules();
        addModuleSystemRules();

        // ═══════════════════════════════════════════════════════════
        // JDK 11 → 17 Changes
        // ═══════════════════════════════════════════════════════════
        addJdk11To17Rules();

        // ═══════════════════════════════════════════════════════════
        // JDK 17 → 21 Changes
        // ═══════════════════════════════════════════════════════════
        addJdk17To21Rules();

        // ═══════════════════════════════════════════════════════════
        // Common deprecated APIs across versions
        // ═══════════════════════════════════════════════════════════
        addCommonDeprecations();
    }

    private void addSunMiscRules() {
        rules.add(DeprecationRule.builder()
                .id("SUN_BASE64_ENCODER")
                .type(DeprecationRule.RuleType.REMOVED)
                .risk(DeprecationRule.RiskLevel.HIGH)
                .removedIn(9)
                .apiPattern("sun.misc.BASE64Encoder")
                .importPattern("import sun.misc.BASE64Encoder")
                .description("sun.misc.BASE64Encoder removed in JDK 9")
                .replacement("java.util.Base64.getEncoder()")
                .replacementImport("import java.util.Base64")
                .autoFixable(true)
                .build());

        rules.add(DeprecationRule.builder()
                .id("SUN_BASE64_DECODER")
                .type(DeprecationRule.RuleType.REMOVED)
                .risk(DeprecationRule.RiskLevel.HIGH)
                .removedIn(9)
                .apiPattern("sun.misc.BASE64Decoder")
                .importPattern("import sun.misc.BASE64Decoder")
                .description("sun.misc.BASE64Decoder removed in JDK 9")
                .replacement("java.util.Base64.getDecoder()")
                .replacementImport("import java.util.Base64")
                .autoFixable(true)
                .build());

        rules.add(DeprecationRule.builder()
                .id("SUN_MISC_UNSAFE")
                .type(DeprecationRule.RuleType.MODULE_RESTRICTION)
                .risk(DeprecationRule.RiskLevel.CRITICAL)
                .removedIn(17)
                .apiPattern("sun.misc.Unsafe")
                .importPattern("import sun.misc.Unsafe")
                .description("sun.misc.Unsafe strongly encapsulated in JDK 17+")
                .replacement("Use VarHandle or MethodHandles for most use cases")
                .autoFixable(false)
                .build());
    }

    private void addJaxbRules() {
        rules.add(DeprecationRule.builder()
                .id("JAXB_REMOVED")
                .type(DeprecationRule.RuleType.REMOVED)
                .risk(DeprecationRule.RiskLevel.HIGH)
                .deprecatedIn(9).removedIn(11)
                .apiPattern("javax.xml.bind")
                .importPattern("import javax.xml.bind")
                .description("JAXB (javax.xml.bind) removed in JDK 11 — add jakarta.xml.bind dependency")
                .replacement("jakarta.xml.bind (add as Maven/Gradle dependency)")
                .replacementImport("import jakarta.xml.bind")
                .autoFixable(true)
                .build());

        rules.add(DeprecationRule.builder()
                .id("JAXB_DATATYPECONVERTER")
                .type(DeprecationRule.RuleType.REMOVED)
                .risk(DeprecationRule.RiskLevel.HIGH)
                .deprecatedIn(9).removedIn(11)
                .apiPattern("javax.xml.bind.DatatypeConverter")
                .importPattern("import javax.xml.bind.DatatypeConverter")
                .description("DatatypeConverter removed with JAXB in JDK 11")
                .replacement("java.util.Base64 or java.util.HexFormat (JDK 17+)")
                .replacementImport("import java.util.Base64")
                .autoFixable(true)
                .build());
    }

    private void addJaxWsRules() {
        rules.add(DeprecationRule.builder()
                .id("JAX_WS_REMOVED")
                .type(DeprecationRule.RuleType.REMOVED)
                .risk(DeprecationRule.RiskLevel.HIGH)
                .deprecatedIn(9).removedIn(11)
                .apiPattern("javax.xml.ws")
                .importPattern("import javax.xml.ws")
                .description("JAX-WS removed in JDK 11 — add jakarta.xml.ws dependency")
                .replacement("jakarta.xml.ws (add as Maven/Gradle dependency)")
                .autoFixable(false)
                .build());

        rules.add(DeprecationRule.builder()
                .id("JAVAX_ACTIVATION")
                .type(DeprecationRule.RuleType.REMOVED)
                .risk(DeprecationRule.RiskLevel.HIGH)
                .deprecatedIn(9).removedIn(11)
                .apiPattern("javax.activation")
                .importPattern("import javax.activation")
                .description("JavaBeans Activation Framework removed in JDK 11")
                .replacement("jakarta.activation (add as Maven/Gradle dependency)")
                .autoFixable(false)
                .build());
    }

    private void addJavaxAnnotationRules() {
        rules.add(DeprecationRule.builder()
                .id("JAVAX_ANNOTATION_GENERATED")
                .type(DeprecationRule.RuleType.REMOVED)
                .risk(DeprecationRule.RiskLevel.MEDIUM)
                .deprecatedIn(9).removedIn(11)
                .apiPattern("javax.annotation.Generated")
                .importPattern("import javax.annotation.Generated")
                .description("javax.annotation.Generated removed in JDK 11")
                .replacement("jakarta.annotation.Generated or javax.annotation.processing.Generated")
                .replacementImport("import javax.annotation.processing.Generated")
                .autoFixable(true)
                .build());
    }

    private void addCorbaRules() {
        rules.add(DeprecationRule.builder()
                .id("CORBA_REMOVED")
                .type(DeprecationRule.RuleType.REMOVED)
                .risk(DeprecationRule.RiskLevel.CRITICAL)
                .deprecatedIn(9).removedIn(11)
                .apiPattern("org.omg.CORBA")
                .importPattern("import org.omg.CORBA")
                .description("CORBA support removed in JDK 11")
                .replacement("Use GlassFish CORBA ORB as external dependency")
                .autoFixable(false)
                .build());
    }

    private void addNashornRules() {
        rules.add(DeprecationRule.builder()
                .id("NASHORN_REMOVED")
                .type(DeprecationRule.RuleType.REMOVED)
                .risk(DeprecationRule.RiskLevel.HIGH)
                .deprecatedIn(11).removedIn(15)
                .apiPattern("jdk.nashorn")
                .importPattern("import jdk.nashorn")
                .description("Nashorn JavaScript engine removed in JDK 15")
                .replacement("Use GraalVM JavaScript engine")
                .autoFixable(false)
                .build());

        rules.add(DeprecationRule.builder()
                .id("SCRIPT_ENGINE_NASHORN")
                .type(DeprecationRule.RuleType.BEHAVIOR_CHANGE)
                .risk(DeprecationRule.RiskLevel.HIGH)
                .deprecatedIn(11).removedIn(15)
                .apiPattern("ScriptEngineManager().getEngineByName(\"nashorn\")")
                .importPattern("import javax.script")
                .description("Nashorn script engine no longer available by default")
                .replacement("Use GraalVM JavaScript or add nashorn-core dependency")
                .autoFixable(false)
                .build());
    }

    private void addJavaFxRules() {
        rules.add(DeprecationRule.builder()
                .id("JAVAFX_REMOVED")
                .type(DeprecationRule.RuleType.REMOVED)
                .risk(DeprecationRule.RiskLevel.CRITICAL)
                .removedIn(11)
                .apiPattern("javafx.")
                .importPattern("import javafx.")
                .description("JavaFX removed from JDK 11+ — use OpenJFX as external dependency")
                .replacement("Add org.openjfx:javafx-controls dependency")
                .autoFixable(false)
                .build());
    }

    private void addModuleSystemRules() {
        rules.add(DeprecationRule.builder()
                .id("JPMS_INTERNAL_API")
                .type(DeprecationRule.RuleType.MODULE_RESTRICTION)
                .risk(DeprecationRule.RiskLevel.HIGH)
                .deprecatedIn(9)
                .apiPattern("--add-opens")
                .description("JDK internal APIs require --add-opens flags in JDK 9+")
                .replacement("Use standard APIs or add --add-opens JVM flags")
                .autoFixable(false)
                .build());

        rules.add(DeprecationRule.builder()
                .id("TOOLS_JAR_REMOVED")
                .type(DeprecationRule.RuleType.REMOVED)
                .risk(DeprecationRule.RiskLevel.HIGH)
                .removedIn(9)
                .apiPattern("tools.jar")
                .description("tools.jar no longer exists in JDK 9+ — APIs are in jdk.compiler module")
                .replacement("Depend on jdk.compiler module")
                .autoFixable(false)
                .build());
    }

    private void addJdk11To17Rules() {
        rules.add(DeprecationRule.builder()
                .id("RMI_ACTIVATION_REMOVED")
                .type(DeprecationRule.RuleType.REMOVED)
                .risk(DeprecationRule.RiskLevel.HIGH)
                .deprecatedIn(15).removedIn(17)
                .apiPattern("java.rmi.activation")
                .importPattern("import java.rmi.activation")
                .description("RMI Activation removed in JDK 17")
                .replacement("No direct replacement — redesign using modern alternatives")
                .autoFixable(false)
                .build());

        rules.add(DeprecationRule.builder()
                .id("SECURITY_MANAGER_DEPRECATED")
                .type(DeprecationRule.RuleType.DEPRECATED)
                .risk(DeprecationRule.RiskLevel.MEDIUM)
                .deprecatedIn(17)
                .apiPattern("SecurityManager")
                .importPattern("import java.lang.SecurityManager")
                .description("SecurityManager deprecated for removal in JDK 17")
                .replacement("Use other security mechanisms")
                .autoFixable(false)
                .build());

        rules.add(DeprecationRule.builder()
                .id("STRONG_ENCAPSULATION")
                .type(DeprecationRule.RuleType.MODULE_RESTRICTION)
                .risk(DeprecationRule.RiskLevel.HIGH)
                .removedIn(16)
                .apiPattern("--illegal-access")
                .description("--illegal-access=permit no longer available in JDK 17")
                .replacement("Use --add-opens for specific packages")
                .autoFixable(false)
                .build());

        rules.add(DeprecationRule.builder()
                .id("APPLET_REMOVED")
                .type(DeprecationRule.RuleType.REMOVED)
                .risk(DeprecationRule.RiskLevel.HIGH)
                .deprecatedIn(9).removedIn(17)
                .apiPattern("java.applet")
                .importPattern("import java.applet")
                .description("Applet API removed in JDK 17")
                .replacement("Use modern web technologies (JavaFX WebView, etc.)")
                .autoFixable(false)
                .build());
    }

    private void addJdk17To21Rules() {
        rules.add(DeprecationRule.builder()
                .id("FINALIZE_DEPRECATED")
                .type(DeprecationRule.RuleType.DEPRECATED)
                .risk(DeprecationRule.RiskLevel.MEDIUM)
                .deprecatedIn(18)
                .apiPattern("finalize()")
                .description("Object.finalize() deprecated for removal")
                .replacement("Use Cleaner or try-with-resources")
                .autoFixable(false)
                .build());

        rules.add(DeprecationRule.builder()
                .id("THREAD_STOP_REMOVE")
                .type(DeprecationRule.RuleType.REMOVED)
                .risk(DeprecationRule.RiskLevel.HIGH)
                .removedIn(21)
                .apiPattern("Thread.stop()")
                .importPattern("")
                .description("Thread.stop() throws UnsupportedOperationException in JDK 21")
                .replacement("Use Thread.interrupt() and cooperative cancellation")
                .autoFixable(false)
                .build());
    }

    private void addCommonDeprecations() {
        rules.add(DeprecationRule.builder()
                .id("DATE_CONSTRUCTOR_DEPRECATED")
                .type(DeprecationRule.RuleType.DEPRECATED)
                .risk(DeprecationRule.RiskLevel.LOW)
                .deprecatedIn(1)
                .apiPattern("new Date(int, int, int)")
                .description("Date constructors with year/month/day deprecated since JDK 1.1")
                .replacement("Use java.time.LocalDate.of()")
                .replacementImport("import java.time.LocalDate")
                .autoFixable(true)
                .build());

        rules.add(DeprecationRule.builder()
                .id("THREAD_DESTROY_REMOVED")
                .type(DeprecationRule.RuleType.REMOVED)
                .risk(DeprecationRule.RiskLevel.HIGH)
                .removedIn(11)
                .apiPattern("Thread.destroy()")
                .description("Thread.destroy() removed")
                .replacement("No replacement — was never implemented")
                .autoFixable(false)
                .build());

        rules.add(DeprecationRule.builder()
                .id("THREAD_SUSPEND_RESUME")
                .type(DeprecationRule.RuleType.DEPRECATED)
                .risk(DeprecationRule.RiskLevel.MEDIUM)
                .deprecatedIn(2)
                .apiPattern("Thread.suspend()")
                .description("Thread.suspend()/resume() deprecated — deadlock prone")
                .replacement("Use wait/notify or java.util.concurrent locks")
                .autoFixable(false)
                .build());

        rules.add(DeprecationRule.builder()
                .id("URL_CONSTRUCTOR")
                .type(DeprecationRule.RuleType.DEPRECATED)
                .risk(DeprecationRule.RiskLevel.LOW)
                .deprecatedIn(20)
                .apiPattern("new URL(")
                .description("URL constructors deprecated in JDK 20")
                .replacement("Use URI.create().toURL()")
                .replacementImport("import java.net.URI")
                .autoFixable(true)
                .build());
    }
}
