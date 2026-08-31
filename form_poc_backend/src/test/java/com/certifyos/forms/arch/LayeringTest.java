package com.certifyos.forms.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the layering claim rather than asserting it.
 *
 * <p>The POC's second thesis is that a DDD decomposition is workable here. A diagram in a design doc
 * cannot demonstrate that — a build that fails when the boundary is crossed can. This is the one
 * quality tool kept after dropping SpotBugs, Checkstyle and Jacoco, precisely because it is the only
 * one testing a claim the POC exists to make.
 */
class LayeringTest {

    private static final String ROOT = "com.certifyos.forms";

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
    }

    @Test
    @DisplayName("domain does not depend on application, infrastructure or interfaces")
    void domainIsIndependent() {
        noClasses()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..application..", "..infrastructure..", "..interfaces..")
                .because("the domain is the stable centre — everything points inward at it, never the reverse")
                .check(classes);
    }

    @Test
    @DisplayName("domain has no persistence types in it")
    void domainHasNoPersistenceTypes() {
        noClasses()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("io.quarkus.mongodb..", "org.bson..", "com.mongodb..")
                .because("Panache and BSON annotations on domain objects is exactly the shortcut that makes a "
                        + "DDD proposal hollow — documents are mapped explicitly in infrastructure instead")
                .check(classes);
    }

    @Test
    @DisplayName("domain has no web types in it")
    void domainHasNoWebTypes() {
        noClasses()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("jakarta.ws..", "org.jboss.resteasy..")
                .because("an aggregate that knows about HTTP status codes has stopped being a model")
                .check(classes);
    }

    @Test
    @DisplayName("domain has no CDI types in it")
    void domainHasNoCdiTypes() {
        noClasses()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("jakarta.enterprise..", "jakarta.inject..")
                .because("domain events go out through a port, so the domain never imports a container")
                .check(classes);
    }

    @Test
    @DisplayName("question_catalog does not reach into form_authoring")
    void catalogDoesNotDependOnAuthoring() {
        noClasses()
                .that()
                .resideInAPackage(ROOT + ".question_catalog..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage(ROOT + ".form_authoring..")
                .because("the catalog is upstream: authoring consumes it, not the other way round")
                .check(classes);
    }

    @Test
    @DisplayName("form_authoring reaches the catalog only through its own domain types and ports")
    void authoringCrossesTheBoundaryDeliberately() {
        // form_authoring may reference catalog domain types (Question, OptionSet, QuestionId) — the
        // published language between the two contexts. What it must not touch is the catalog's
        // internals: its application services, persistence or REST layer.
        noClasses()
                .that()
                .resideInAPackage(ROOT + ".form_authoring..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        ROOT + ".question_catalog.application..",
                        ROOT + ".question_catalog.infrastructure..",
                        ROOT + ".question_catalog.interfaces..")
                .because("cross-context access goes through QuestionCatalogPort, which is the seam that lets "
                        + "the catalog become a separate service later without the domain noticing")
                .check(classes);
    }

    @Test
    @DisplayName("the shared kernel depends on no bounded context")
    void sharedKernelIsSelfContained() {
        noClasses()
                .that()
                .resideInAPackage(ROOT + ".shared_kernel..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(ROOT + ".question_catalog..", ROOT + ".form_authoring..")
                .because("the expression grammar is a published language — if it depended on a context it "
                        + "would stop being shareable, and the FE↔BE contract would lose its meaning")
                .check(classes);
    }
}
