package com.certifyos.forms.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.certifyos.forms.form_authoring.domain.definition.FormDefinition;
import com.certifyos.forms.form_authoring.domain.definition.Step;
import com.certifyos.forms.form_authoring.interfaces.TestRepositories;
import com.certifyos.forms.shared_kernel.expression.Expression;
import com.certifyos.forms.shared_kernel.expression.Operator;
import com.certifyos.forms.support.InMemoryRepositories;
import io.quarkus.runtime.StartupEvent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Seeding must not discard an author's work.
 *
 * <p>It used to. The seeder saved unconditionally on every startup, so changing a rule in the studio
 * and restarting the backend silently restored the fixture value — same document id, no error,
 * nothing to notice. That is the worst shape a data-loss bug can take, and it is invisible to every
 * other test in this suite because they construct their own state.
 *
 * <p>Runs against the real fixture files rather than a stub, since the path resolution is itself
 * something that has already broken once: {@code quarkusDev} launches the JVM from
 * {@code build/classes/java/main}, so the relative default resolved to nothing and the service
 * started empty behind a single WARN line.
 */
class SeedRunnerTest {

    private InMemoryRepositories.Forms forms;
    private InMemoryRepositories.Sections sections;
    private TestRepositories.Questions questions;
    private TestRepositories.OptionSets optionSets;
    private InMemoryRepositories.Templates templates;
    private InMemoryRepositories.Blueprints blueprints;
    private TestRepositories.Categories categories;
    private SeedRunner runner;

    @BeforeEach
    void setUp() {
        forms = new InMemoryRepositories.Forms();
        sections = new InMemoryRepositories.Sections();
        questions = new TestRepositories.Questions();
        optionSets = new TestRepositories.OptionSets();
        categories = new TestRepositories.Categories();

        templates = new InMemoryRepositories.Templates();
        blueprints = new InMemoryRepositories.Blueprints();
        runner = new SeedRunner(questions, optionSets, categories, templates, blueprints, sections, forms);
        runner.enabled = true;
        runner.force = false;
        runner.fixturesDir = "../form_poc_shared/fixtures";
    }

    /** The edit a restart must not undo. */
    private FormDefinition editedFixtureForm() {
        return new FormDefinition(
                "fd_fl_blue_recred",
                "tenant_fl_blue",
                null,
                "Edited by an author",
                "practitioner",
                null,
                null,
                Map.of(),
                List.of(Step.of("licensure", "sd_licensure", 10)
                        .withVisibleWhen(new Expression.Leaf("applicantDetails.providerType", Operator.EQ, "MD"))),
                List.of(),
                FormDefinition.DefinitionStatus.DRAFT);
    }

    @Test
    @DisplayName("seeds into an empty database")
    void seedsWhenEmpty() {
        runner.onStart(new StartupEvent());

        assertTrue(forms.findById("fd_fl_blue_recred").isPresent(), "the fixture form should be loaded");
        assertFalse(questions.findActiveFor("tenant_fl_blue").isEmpty(), "the catalog should be loaded");
        // The reusable shapes the section definitions reference. Before these existed, the API
        // returned sourceTemplateId values pointing at nothing.
        assertFalse(templates.findAvailableFor("tenant_fl_blue").isEmpty(), "templates should be loaded");
        assertFalse(blueprints.findAvailableFor("tenant_fl_blue").isEmpty(), "blueprints should be loaded");
    }

    @Test
    @DisplayName("leaves an existing form alone — an author's edit survives a restart")
    void doesNotOverwriteExistingData() {
        forms.save(editedFixtureForm());

        runner.onStart(new StartupEvent());

        FormDefinition after = forms.require("fd_fl_blue_recred");
        assertEquals("Edited by an author", after.name(), "seeding overwrote an edited form");
        assertEquals(
                new Expression.Leaf("applicantDetails.providerType", Operator.EQ, "MD"),
                after.step("licensure").orElseThrow().visibleWhen(),
                "seeding restored the fixture's condition over the author's");
    }

    @Test
    @DisplayName("force=true overwrites, for the case where a reset is what you actually want")
    void forceOverwrites() {
        forms.save(editedFixtureForm());
        runner.force = true;

        runner.onStart(new StartupEvent());

        // The escape hatch has to exist — resetting a demo is a real need — but it must be asked for.
        assertEquals(
                "Florida Blue Recred Practitioner Application",
                forms.require("fd_fl_blue_recred").name());
    }

    @Test
    @DisplayName("disabled means disabled, even on an empty database")
    void respectsDisabled() {
        runner.enabled = false;

        runner.onStart(new StartupEvent());

        assertTrue(forms.findById("fd_fl_blue_recred").isEmpty());
    }

    @Test
    @DisplayName("a missing fixtures directory is survivable, not fatal")
    void missingFixturesDoesNotThrow() {
        runner.fixturesDir = "../no_such_directory/fixtures";

        runner.onStart(new StartupEvent());

        assertTrue(forms.findById("fd_fl_blue_recred").isEmpty());
    }
}
