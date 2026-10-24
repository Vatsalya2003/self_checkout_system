package com.selfcheckout.self_checkout.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * checkout-service's layer rules, as an executable test.
 *
 * Week 2 introduced these rules for the whole application; in Week 4 each
 * service checks its own. Since Week 5 checkout-service has five layers:
 *
 *   api -> transactions -> scanlog -> remote
 *               |  \________________________^
 *               v
 *          persistence
 *
 * scanlog: the transactions layer reaches it through exactly one interface,
 * ScanRecorder; it numbers scans and pushes them to analytics-service.
 * remote: the outbound HTTP clients (inventory, analytics, and the catalog
 * client from the catalog-client library). It is the only layer besides the
 * API allowed to know about HTTP, and it depends on nothing above it.
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
                .layer("Remote").definedBy("..remote..")
                .whereLayer("API").mayNotBeAccessedByAnyLayer()
                .whereLayer("Transactions").mayOnlyBeAccessedByLayers("API")
                .whereLayer("ScanLog").mayOnlyBeAccessedByLayers("Transactions")
                // Remote may see persistence's CatalogCache interface: the
                // HTTP catalog client implements it.
                .whereLayer("Persistence").mayOnlyBeAccessedByLayers("Transactions", "ScanLog", "Remote")
                .whereLayer("Remote").mayOnlyBeAccessedByLayers("Transactions", "ScanLog")
                // The domain exceptions (transactions.exception, from the
                // shared-web library) are the shared error vocabulary: any
                // layer may throw them. The catalog client throws
                // ServiceUnavailableException when catalog-service is down.
                .ignoreDependency(resideInAPackage("..remote.."), resideInAPackage("..transactions.exception.."))
                .check(classes);
    }

    @Test
    @DisplayName("HTTP and web types stay inside the API layer")
    void httpTypesDoNotLeakOutOfApi() {
        noClasses().that().resideOutsideOfPackages("..api..", "..remote..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework.http..", "org.springframework.web..")
                .because("only the API layer (inbound) and the remote layer (outbound) may know about HTTP")
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
                .should().dependOnClassesThat().resideInAnyPackage("..api..", "..transactions..", "..scanlog..", "..remote..")
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
    @DisplayName("remote depends on nothing above it")
    void remoteDependsOnNothingAbove() {
        // (the domain exceptions in transactions.exception are allowed, see above)
        noClasses().that().resideInAPackage("..remote..")
                .should().dependOnClassesThat().resideInAnyPackage("..api..", "..scanlog..",
                        "com.selfcheckout.self_checkout.transactions", "..transactions.result..")
                .because("outbound clients report outcomes; the saga above decides what they mean")
                .check(classes);
    }

    @Test
    @DisplayName("config wires beans and depends on no business layer")
    void configDependsOnNoLayer() {
        noClasses().that().resideInAPackage("..config..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..api..", "..transactions..", "..scanlog..", "..persistence..")
                .because("configuration only wires the remote clients; it must not depend on business code")
                .check(classes);
    }
}
