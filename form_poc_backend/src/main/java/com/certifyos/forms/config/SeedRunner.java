package com.certifyos.forms.config;

import com.certifyos.forms.form_authoring.domain.definition.FormDefinition;
import com.certifyos.forms.form_authoring.domain.port.FormBlueprintRepository;
import com.certifyos.forms.form_authoring.domain.port.FormDefinitionRepository;
import com.certifyos.forms.form_authoring.domain.port.SectionDefinitionRepository;
import com.certifyos.forms.form_authoring.domain.port.SectionTemplateRepository;
import com.certifyos.forms.question_catalog.domain.port.OptionSetRepository;
import com.certifyos.forms.question_catalog.domain.port.QuestionCategoryRepository;
import com.certifyos.forms.question_catalog.domain.port.QuestionRepository;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Loads the fixtures on startup so a fresh clone has something to look at.
 *
 * <p>Seeding failures are logged and swallowed rather than blocking startup. That is a POC-only
 * judgement, and the reason is specific: the fixtures live outside the module, so a working
 * directory that differs from the one Gradle uses would otherwise turn a missing file into a service
 * that will not boot. Given the same fixtures are compiled by
 * {@code RealFixtureCompilationTest}, a genuine problem with them surfaces in CI first.
 *
 * <p>The relative path is resolved by searching upward rather than trusting the working directory,
 * because the working directory is not what you would guess: {@code quarkusDev} runs the JVM from
 * {@code build/classes/java/main}, so {@code ../form_poc_shared/fixtures} resolved inside
 * {@code build/classes/java} and found nothing. The service started empty and the only clue was one
 * WARN line — exactly the failure the swallowing above was designed to survive, which is why it
 * needed fixing rather than defending.
 */
@ApplicationScoped
public class SeedRunner {

    private static final Logger LOG = Logger.getLogger(SeedRunner.class);

    @ConfigProperty(name = "form-poc.seed.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "form-poc.seed.fixtures-dir", defaultValue = "../form_poc_shared/fixtures")
    String fixturesDir;

    /**
     * Re-seed over data that is already there.
     *
     * <p>Off by default, because seeding unconditionally on every start silently discarded work: you
     * would change a rule in the studio, restart the backend, and find the fixture value back. The
     * form definition is the same document either way, so there was no error and nothing to notice.
     */
    @ConfigProperty(name = "form-poc.seed.force", defaultValue = "false")
    boolean force;

    private final QuestionRepository questions;
    private final OptionSetRepository optionSets;
    private final QuestionCategoryRepository categories;
    private final SectionTemplateRepository templates;
    private final FormBlueprintRepository blueprints;
    private final SectionDefinitionRepository sections;
    private final FormDefinitionRepository forms;

    @Inject
    public SeedRunner(
            QuestionRepository questions,
            OptionSetRepository optionSets,
            QuestionCategoryRepository categories,
            SectionTemplateRepository templates,
            FormBlueprintRepository blueprints,
            SectionDefinitionRepository sections,
            FormDefinitionRepository forms) {
        this.questions = questions;
        this.optionSets = optionSets;
        this.categories = categories;
        this.templates = templates;
        this.blueprints = blueprints;
        this.sections = sections;
        this.forms = forms;
    }

    /**
     * Finds the fixtures directory without depending on the working directory.
     *
     * <p>An absolute setting is honoured as given. A relative one is tried against the working
     * directory first, then against each ancestor — so the same default works whether the JVM was
     * launched from the project root, from {@code build/classes/java/main} under dev mode, or from a
     * jar somewhere else.
     *
     * @return the directory, or null when nothing matched
     */
    private static Path resolveFixturesDir(String configured) {
        Path candidate = Path.of(configured);
        if (candidate.isAbsolute()) {
            return java.nio.file.Files.isDirectory(candidate) ? candidate : null;
        }

        Path base = Path.of("").toAbsolutePath();
        // Bounded rather than looping to the filesystem root: past a handful of levels a match would
        // be a coincidence, not this repository.
        for (int depth = 0; depth <= 6 && base != null; depth++) {
            Path attempt = base.resolve(candidate).normalize();
            if (java.nio.file.Files.isDirectory(attempt)) {
                return attempt;
            }
            base = base.getParent();
        }
        return null;
    }

    void onStart(@Observes StartupEvent event) {
        if (!enabled) {
            return;
        }
        Path resolved = resolveFixturesDir(fixturesDir);
        if (resolved == null) {
            LOG.warnf(
                    "Seed skipped — no fixtures found for '%s', searched upward from %s",
                    fixturesDir, Path.of("").toAbsolutePath());
            return;
        }
        FixtureLoader loader = new FixtureLoader(resolved);
        if (!loader.available()) {
            LOG.warnf("Seed skipped — no fixtures at %s", resolved);
            return;
        }
        try {
            FixtureLoader.Fixtures fixtures = loader.load();

            // Seed only into an empty database. Checked per fixture form rather than by counting a
            // collection, because the thing worth protecting is an author's edits to *these*
            // documents — and the check needs no port method that exists solely for seeding.
            List<String> present = fixtures.forms().stream()
                    .map(FormDefinition::id)
                    .filter(id -> forms.findById(id).isPresent())
                    .toList();

            if (!present.isEmpty() && !force) {
                LOG.infof(
                        "Seed skipped — %s already present. Set form-poc.seed.force=true to overwrite,"
                                + " or drop the database.",
                        present);
                return;
            }
            if (!present.isEmpty()) {
                LOG.warnf("form-poc.seed.force=true — overwriting %s and discarding any edits", present);
            }

            LOG.infof("Seeding from %s", resolved);
            // Categories before questions: a question carries a category key, and seeding the
            // reference before the thing it points at is how a fixture set ends up internally
            // inconsistent the first time anything validates it.
            fixtures.questionCategories().forEach(categories::save);
            fixtures.optionSets().forEach(optionSets::save);
            fixtures.questions().forEach(questions::save);
            // Templates and blueprints before the definitions that reference them, so a mid-seed
            // failure never leaves a definition pointing at a template that was never written.
            fixtures.sectionTemplates().forEach(templates::save);
            fixtures.formBlueprints().forEach(blueprints::save);
            fixtures.sections().forEach(sections::save);
            fixtures.forms().forEach(forms::save);

            LOG.infof(
                    "Seeded %d questions, %d option sets, %d templates, %d blueprint(s), %d sections," + " %d form(s)",
                    fixtures.questions().size(),
                    fixtures.optionSets().size(),
                    fixtures.sectionTemplates().size(),
                    fixtures.formBlueprints().size(),
                    fixtures.sections().size(),
                    fixtures.forms().size());
        } catch (Exception e) {
            LOG.errorf(e, "Seed failed — the API will start empty");
        }
    }
}
