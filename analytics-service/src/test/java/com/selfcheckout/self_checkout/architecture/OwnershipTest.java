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
 * analytics-service's table ownership, as an executable test.
 *
 * Week 5: analytics-service has its own database (analytics_db) with two
 * tables, and each has exactly one writing class: JdbcScanStore (the scans
 * checkout pushes to POST /internal/scans) and JdbcSnapshotWriter (the sink's
 * windows). Item names come from catalog-service over HTTP.
 *
 * ArchUnit cannot read SQL strings, so this checks the next best thing: which
 * classes are allowed to make a call that can write at all. The database also
 * refuses anything else (db/analytics/grants.sql), and no other service can
 * even connect to analytics_db.
 */
class OwnershipTest {

    // Only our own production code (shared libraries included, because they
    // are on this service's classpath and ship inside its jar).
    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.selfcheckout.self_checkout");

    /**
     * "Writes to the database": any call that can change a row. JdbcTemplate's
     * update/batchUpdate/execute, a raw executeUpdate/executeBatch, and the
     * JPA repository/EntityManager mutators. Reads (query, find*) are fine.
     */
    static final DescribedPredicate<JavaMethodCall> A_DATABASE_WRITE =
            new DescribedPredicate<>("a database write") {
                @Override
                public boolean test(JavaMethodCall call) {
                    String owner = call.getTargetOwner().getName();
                    String m = call.getName();
                    if (owner.equals("org.springframework.jdbc.core.JdbcTemplate")) {
                        return m.equals("update") || m.equals("batchUpdate") || m.equals("execute");
                    }
                    if (owner.equals("java.sql.PreparedStatement") || owner.equals("java.sql.Statement")) {
                        return m.startsWith("executeUpdate") || m.startsWith("executeBatch")
                                || m.startsWith("executeLargeUpdate");
                    }
                    if (owner.equals("jakarta.persistence.EntityManager")) {
                        return m.equals("persist") || m.equals("merge") || m.equals("remove");
                    }
                    if (call.getTargetOwner().isAssignableTo("org.springframework.data.repository.Repository")) {
                        return m.startsWith("save") || m.startsWith("delete") || m.startsWith("flush");
                    }
                    return false;
                }
            };

    @Test
    @DisplayName("only the scan store and the snapshot writer write to the database")
    void onlyTheTwoWritersWrite() {
        noClasses().that().doNotHaveSimpleName("JdbcSnapshotWriter")
                .and().doNotHaveSimpleName("JdbcScanStore")
                .should().callMethodWhere(A_DATABASE_WRITE)
                .because("analytics-service writes scan_log (pushed scans) and popular_snapshots (the sink), nothing else")
                .check(classes);
    }

    @Test
    @DisplayName("layers only depend downwards")
    void layerDependenciesAreRespected() {
        layeredArchitecture()
                .consideringOnlyDependenciesInLayers()
                .layer("API").definedBy("..api..")
                .layer("Business").definedBy("..analytics..")
                .layer("Persistence").definedBy("..persistence..")
                .whereLayer("API").mayNotBeAccessedByAnyLayer()
                .whereLayer("Business").mayOnlyBeAccessedByLayers("API")
                .whereLayer("Persistence").mayOnlyBeAccessedByLayers("Business")
                .check(classes);
    }

    @Test
    @DisplayName("HTTP and web types stay inside the API layer")
    void httpTypesDoNotLeakOutOfApi() {
        // ..remote.. is the catalog client (catalog-client library): an
        // outbound HTTP adapter, so it is allowed to know about HTTP too.
        noClasses().that().resideOutsideOfPackages("..api..", "..remote..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework.http..", "org.springframework.web..")
                .because("only the API layer may know about HTTP; business layers throw domain exceptions instead")
                .check(classes);
    }
}
