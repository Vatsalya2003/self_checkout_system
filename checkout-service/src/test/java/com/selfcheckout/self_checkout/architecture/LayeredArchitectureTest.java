package com.selfcheckout.self_checkout.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * checkout-service's layer rules, as an executable test.
 *
 * Week 2 introduced these rules for the whole application; in Week 4 each
 * service checks its own. checkout-service has four layers:
 *
 *   api -> transactions -> scanlog -> persistence
 *                     \-----------------^   (transactions may also use persistence directly)
 *
 * scanlog replaces the old "analytics" layer here: the transactions layer
 * reaches it through exactly one interface, ScanRecorder, and it appends scans
 * to scan_log for analytics-service. Analytics itself is no longer in this
 * process at all.
 *
 * The test reads compiled .class files; it never starts Spring.
 */
class LayeredArchitectureTest {

    // Only our own production code (shared libraries included, because they
    // are on this service's classpath and ship inside its jar).
    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.selfcheckout.self_checkout");

    @Test
    @DisplayName("layers only depend downwards")
    void layerDependenciesAreRespected() {
        layeredArchitecture()
                .consideringOnlyDependenciesInLayers()
                .layer("API").definedBy("..api..")
                .layer("Transactions").definedBy("..transactions..")
                .layer("ScanLog").definedBy("..scanlog..")
                .layer("Persistence").definedBy("..persistence..")
                .whereLayer("API").mayNotBeAccessedByAnyLayer()
                .whereLayer("Transactions").mayOnlyBeAccessedByLayers("API")
                .whereLayer("ScanLog").mayOnlyBeAccessedByLayers("Transactions")
                .whereLayer("Persistence").mayOnlyBeAccessedByLayers("Transactions", "ScanLog")
                .check(classes);
    }

    @Test
    @DisplayName("HTTP and web types stay inside the API layer")
    void httpTypesDoNotLeakOutOfApi() {
        noClasses().that().resideOutsideOfPackage("..api..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework.http..", "org.springframework.web..")
                .because("only the API layer may know about HTTP; business layers throw domain exceptions instead")
                .check(classes);
    }

    @Test
    @DisplayName("the API layer never touches persistence")
    void apiDoesNotDependOnPersistence() {
        noClasses().that().resideInAPackage("..api..")
                .should().dependOnClassesThat().resideInAnyPackage("..persistence..")
                .because("controllers must go through a business layer")
                .check(classes);
    }

    @Test
    @DisplayName("persistence does not depend on any layer above it")
    void persistenceDependsOnNothingAbove() {
        noClasses().that().resideInAPackage("..persistence..")
                .should().dependOnClassesThat().resideInAnyPackage("..api..", "..transactions..", "..scanlog..")
                .because("the database access layer is the bottom of the stack")
                .check(classes);
    }

    @Test
    @DisplayName("scanlog does not depend on transactions or the API")
    void scanLogDependsOnNothingAbove() {
        noClasses().that().resideInAPackage("..scanlog..")
                .should().dependOnClassesThat().resideInAnyPackage("..api..", "..transactions..")
                .because("the transactions -> scanlog arrow must stay one-directional")
                .check(classes);
    }

    @Test
    @DisplayName("config is a shared kernel and depends on no layer")
    void configDependsOnNoLayer() {
        noClasses().that().resideInAPackage("..config..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..api..", "..transactions..", "..scanlog..", "..persistence..")
                .because("shared configuration must not depend on the layers that read it")
                .check(classes);
    }
}
