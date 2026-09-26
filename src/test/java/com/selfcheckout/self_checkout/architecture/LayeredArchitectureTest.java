package com.selfcheckout.self_checkout.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The layer rules, as an executable test.
 *
 * Week 1's layering was a naming convention: nothing stopped a controller from
 * reaching straight into a repository, and in fact HTTP types had already
 * leaked down into the service layer. A convention that nothing checks is a
 * convention that eventually gets broken, so this week the rules are a test
 * that fails the build.
 *
 * These rules are the exact same arrows drawn in the diagram in
 * ARCHITECTURE.md -- if you change one, change the other.
 *
 * Note this test never starts Spring. It reads the compiled .class files, so
 * it is fast and does not touch the database.
 */
class LayeredArchitectureTest {

    // Only our own production code. DoNotIncludeTests keeps the test classes
    // themselves (including this one) out of the analysis.
    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.selfcheckout.self_checkout");

    @Test
    @DisplayName("layers only depend downwards")
    void layerDependenciesAreRespected() {
        ArchRule rule = layeredArchitecture()
                .consideringOnlyDependenciesInLayers()

                .layer("API").definedBy("..api..")
                .layer("Transactions").definedBy("..transactions..")
                .layer("Analytics").definedBy("..analytics..")
                .layer("Persistence").definedBy("..persistence..")

                // Nothing sits above the API layer.
                .whereLayer("API").mayNotBeAccessedByAnyLayer()

                // Transactions is CLOSED: only the API may call into it.
                .whereLayer("Transactions").mayOnlyBeAccessedByLayers("API")

                // Analytics is OPEN: the API reaches it directly for
                // GET /analytics/popular-items, and transactions reaches it
                // through ScanRecorder when a scan happens. Letting the API
                // skip transactions here is deliberate -- routing a read-only
                // query through a transactions method that does nothing but
                // forward the call would be a sinkhole.
                .whereLayer("Analytics").mayOnlyBeAccessedByLayers("API", "Transactions")

                // Persistence is CLOSED: only the business layers touch JPA.
                .whereLayer("Persistence").mayOnlyBeAccessedByLayers("Transactions", "Analytics");

        rule.check(classes);
    }

    @Test
    @DisplayName("HTTP and web types stay inside the API layer")
    void httpTypesDoNotLeakOutOfApi() {
        // This is the rule Week 1 broke: ApiException carried an HttpStatus,
        // so every service that threw one knew about HTTP. Business rules
        // should not know they are being served over HTTP.
        noClasses()
                .that().resideOutsideOfPackage("..api..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework.http..", "org.springframework.web..")
                .because("only the API layer may know about HTTP; "
                         + "business layers throw domain exceptions instead")
                .check(classes);
    }

    @Test
    @DisplayName("the API layer never touches persistence")
    void apiDoesNotDependOnPersistence() {
        // Keeps entities and repositories out of controllers. If an entity
        // reached the API it could be serialized straight to JSON, and the
        // wire format would silently follow the database schema.
        noClasses()
                .that().resideInAPackage("..api..")
                .should().dependOnClassesThat().resideInAnyPackage("..persistence..")
                .because("controllers must go through a business layer; "
                         + "entities and repositories must never reach the API")
                .check(classes);
    }

    @Test
    @DisplayName("persistence does not depend on any layer above it")
    void persistenceDependsOnNothingAbove() {
        noClasses()
                .that().resideInAPackage("..persistence..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..api..", "..transactions..", "..analytics..")
                .because("the database access layer is the bottom of the stack")
                .check(classes);
    }

    @Test
    @DisplayName("analytics does not depend on transactions or the API")
    void analyticsDependsOnNothingAbove() {
        // The transactions -> analytics arrow must stay one-directional,
        // otherwise the two would be mutually dependent and neither could be
        // understood, tested or replaced on its own.
        noClasses()
                .that().resideInAPackage("..analytics..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..api..", "..transactions..")
                .because("analytics sits below transactions; the dependency runs one way")
                .check(classes);
    }

    @Test
    @DisplayName("config is a shared kernel and depends on no layer")
    void configDependsOnNoLayer() {
        // SelfCheckoutProperties is read by both business layers, so it sits
        // outside the four layers rather than inside one of them. That is only
        // safe as long as it stays a leaf: plain settings, no logic, no
        // dependency on anything above it.
        noClasses()
                .that().resideInAPackage("..config..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..api..", "..transactions..", "..analytics..", "..persistence..")
                .because("shared configuration must not depend on the layers that read it")
                .check(classes);
    }
}
