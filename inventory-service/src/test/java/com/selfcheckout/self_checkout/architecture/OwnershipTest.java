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
 * inventory-service's table ownership, as an executable test.
 *
 * All four services share one database, so "which service may change which
 * table" is the coupling that matters (SERVICES.md has the full matrix).
 * inventory-service writes NO table: it only reads items and low_stock_alerts
 * (both written by checkout-service, inside the payment's transaction).
 *
 * ArchUnit cannot read SQL strings, so this checks the next best thing: which
 * classes are allowed to make a call that can write at all. The table-level
 * rule is also enforced by the database itself (per-service roles and GRANTs,
 * db/roles.sql).
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
    @DisplayName("inventory-service makes no database write of any kind")
    void writesNothing() {
        noClasses().should().callMethodWhere(A_DATABASE_WRITE)
                .because("inventory-service only reports stock; checkout-service changes it")
                .check(classes);
    }

    @Test
    @DisplayName("layers only depend downwards")
    void layerDependenciesAreRespected() {
        layeredArchitecture()
                .consideringOnlyDependenciesInLayers()
                .layer("API").definedBy("..api..")
                .layer("Business").definedBy("..inventory..")
                .layer("Persistence").definedBy("..persistence..")
                .whereLayer("API").mayNotBeAccessedByAnyLayer()
                .whereLayer("Business").mayOnlyBeAccessedByLayers("API")
                .whereLayer("Persistence").mayOnlyBeAccessedByLayers("Business")
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
}
