package com.certifyos.forms.form_authoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.certifyos.forms.form_authoring.application.RulesInventory;
import com.certifyos.forms.form_authoring.domain.definition.FormDefinition;
import com.certifyos.forms.form_authoring.domain.definition.Layout;
import com.certifyos.forms.form_authoring.domain.definition.Origin;
import com.certifyos.forms.form_authoring.domain.definition.QuestionInstance;
import com.certifyos.forms.form_authoring.domain.definition.SectionDefinition;
import com.certifyos.forms.form_authoring.domain.definition.Step;
import com.certifyos.forms.question_catalog.domain.QuestionId;
import com.certifyos.forms.shared_kernel.expression.Expression;
import com.certifyos.forms.shared_kernel.expression.Operator;
import com.certifyos.forms.support.InMemoryRepositories;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Every rule a tenant has, in one read.
 *
 * <p>The screen this backs answers a question the studio could not answer at all: conditions were
 * visible one step at a time, inside the editor of the form that owned them, so "which rules exist"
 * and "what reads from this question" meant opening every form in turn.
 *
 * <p>Two behaviours carry most of the weight here, and both are easy to get subtly wrong in a way
 * that looks fine:
 *
 * <ul>
 *   <li><b>{@code reads} follows refs.</b> A step gated entirely through a named condition depends on
 *       whatever that condition reads. Stopping at the ref would report it as depending on nothing,
 *       which is the opposite of the truth and would make the reverse index useless exactly where it
 *       matters most.
 *   <li><b>Unconditioned steps are listed.</b> The most consequential fact about a rule set is often
 *       the rule that is absent — three Florida Blue sections ship unconditioned in production, a
 *       defect indistinguishable from a working form.
 * </ul>
 */
class RulesInventoryTest {

    private static final String TENANT = "tenant_fl";

    private InMemoryRepositories.Forms forms;
    private InMemoryRepositories.Sections sections;
    private InMemoryRepositories.Questions questions;
    private RulesInventory inventory;

    @BeforeEach
    void setUp() {
        forms = new InMemoryRepositories.Forms();
        sections = new InMemoryRepositories.Sections();
        questions = new InMemoryRepositories.Questions();
        inventory = new RulesInventory(forms, sections, questions);
    }

    @Nested
    @DisplayName("step rules")
    class StepRules {

        @Test
        @DisplayName("lists every step, conditioned or not, so a missing rule is visible")
        void listsUnconditionedSteps() {
            seedRecred();

            RulesInventory.Inventory result = inventory.of(TENANT);

            assertEquals(3, result.steps().size());
            assertEquals(2, result.summary().conditionedSteps(), "two of the three carry a rule");

            RulesInventory.StepRule open = step(result, "applicantDetails");
            assertNull(open.visibleWhen(), "an unconditioned step is present with a null rule, not absent");
            assertTrue(open.reads().isEmpty());
        }

        @Test
        @DisplayName("reads resolves through a ref to the paths the named condition depends on")
        void readsFollowsRefs() {
            seedRecred();

            RulesInventory.StepRule dea = step(inventory.of(TENANT), "deaRegistration");

            // The step's own expression names no path at all — it is `not(ref(specialtyExempt))`.
            // Reporting an empty `reads` here would be defensible and useless: the whole point of the
            // column is answering "what breaks if I change this question".
            assertEquals(List.of("applicantDetails.specialty"), dea.reads());
            assertEquals(List.of("specialtyExempt"), dea.references());
        }

        @Test
        @DisplayName("operators are the step's own, not those inside a condition it references")
        void operatorsExcludeRefTargets() {
            RulesInventory.StepRule dea = step(seedAndRead(), "deaRegistration");

            // `in` belongs to specialtyExempt, which the inventory lists in its own right. Counting it
            // here too would double-count every shared rule and overstate operator usage.
            assertTrue(dea.operators().isEmpty(), "a bare ref uses no operator of its own");
            assertEquals(List.of("nin"), step(seedAndRead(), "licensure").operators());
        }

        @Test
        @DisplayName("steps come back in order, so the list reads like the form")
        void ordered() {
            seedRecred();

            List<String> keys = inventory.of(TENANT).steps().stream()
                    .map(RulesInventory.StepRule::stepKey)
                    .toList();

            assertEquals(List.of("applicantDetails", "licensure", "deaRegistration"), keys);
        }

        @Test
        @DisplayName("the title is the section's name, because a step key is not what an author reads")
        void titleComesFromTheSection() {
            seedRecred();

            assertEquals(
                    "DEA Registration",
                    step(inventory.of(TENANT), "deaRegistration").stepTitle());
        }
    }

    @Nested
    @DisplayName("named rules")
    class NamedRules {

        @Test
        @DisplayName("names the steps using each rule — the reason naming a rule is worth doing")
        void reportsUsage() {
            seedRecred();

            RulesInventory.NamedRule exempt = named(inventory.of(TENANT), "specialtyExempt");

            assertEquals(List.of("deaRegistration"), exempt.usedBySteps());
            assertEquals("Specialty exempt from DEA requirements", exempt.label());
        }

        @Test
        @DisplayName("an unused rule is surfaced rather than hidden, because it is usually a mistake")
        void countsUnused() {
            seedRecred();
            forms.save(withCondition(
                    forms.findById("fd_recred").orElseThrow(),
                    "orphan",
                    "Nothing uses this",
                    new Expression.Leaf("applicantDetails.npi", Operator.EXISTS, null)));

            RulesInventory.Inventory result = inventory.of(TENANT);

            assertEquals(1, result.summary().unusedNamedConditions());
            assertTrue(named(result, "orphan").usedBySteps().isEmpty());
        }
    }

    @Nested
    @DisplayName("question rules")
    class QuestionRules {

        @Test
        @DisplayName("says where a question rule takes effect, which the section editor cannot")
        void namesEveryPlacement() {
            seedRecred();

            // One address section placed twice. The rule is authored once on the section and bites in
            // both placements — invisible from the section editor, which knows nothing about forms.
            sections.with(new SectionDefinition(
                    "sd_address",
                    TENANT,
                    "address",
                    "Address",
                    null,
                    null,
                    null,
                    List.of(question("line2", new Expression.Leaf("line1", Operator.EXISTS, null))),
                    true));

            FormDefinition form = forms.findById("fd_recred").orElseThrow();
            forms.save(form.placeStep(Step.of("practiceLocation", "sd_address", 40))
                    .placeStep(Step.of("billingAddress", "sd_address", 50)));

            RulesInventory.QuestionRule rule = inventory.of(TENANT).questions().stream()
                    .filter(q -> q.questionKey().equals("line2"))
                    .findFirst()
                    .orElseThrow();

            assertEquals(2, rule.placedInSteps().size());
            assertTrue(rule.placedInSteps().stream().anyMatch(p -> p.endsWith("practiceLocation")));
            assertTrue(rule.placedInSteps().stream().anyMatch(p -> p.endsWith("billingAddress")));
        }

        @Test
        @DisplayName("a bare sibling reference is reported as a real answer path, once per placement")
        void qualifiesBareSiblingReads() {
            seedRecred();
            sections.with(new SectionDefinition(
                    "sd_address",
                    TENANT,
                    "address",
                    "Address",
                    null,
                    null,
                    null,
                    // Bare, as a reusable section must be: it cannot know its placement key.
                    List.of(instance("line1", "q_line1", null), questionWithRule("line2", "line1")),
                    true));

            FormDefinition form = forms.findById("fd_recred").orElseThrow();
            forms.save(form.placeStep(Step.of("practiceLocation", "sd_address", 40))
                    .placeStep(Step.of("billingAddress", "sd_address", 50)));

            RulesInventory.QuestionRule rule = inventory.of(TENANT).questions().stream()
                    .filter(q -> q.questionKey().equals("line2"))
                    .findFirst()
                    .orElseThrow();

            // Both are true, and both matter: reporting the bare key would break the reverse index
            // (searching for practiceLocation.line1 would not find this rule) and would leave the
            // label map — keyed by answer path — with nothing to resolve.
            assertEquals(List.of("practiceLocation.line1", "billingAddress.line1"), rule.reads());
        }

        @Test
        @DisplayName("an unplaced section's bare key is returned as authored, there being no answer")
        void unplacedSectionKeepsBareKeys() {
            seedRecred();
            sections.with(new SectionDefinition(
                    "sd_orphan",
                    TENANT,
                    "orphan",
                    "Orphan",
                    null,
                    null,
                    null,
                    List.of(instance("a", "q_a", null), questionWithRule("b", "a")),
                    true));

            RulesInventory.QuestionRule rule = inventory.of(TENANT).questions().stream()
                    .filter(q -> q.questionKey().equals("b"))
                    .findFirst()
                    .orElseThrow();

            assertEquals(List.of("a"), rule.reads());
            assertTrue(rule.placedInSteps().isEmpty());
        }

        @Test
        @DisplayName("a cross-section reference is left qualified, being an answer path already")
        void crossSectionReadKeptAsIs() {
            seedRecred();
            sections.with(new SectionDefinition(
                    "sd_dea",
                    TENANT,
                    "dea",
                    "DEA Registration",
                    null,
                    null,
                    null,
                    List.of(questionWithRule("deaNumber", "applicantDetails.specialty")),
                    true));

            RulesInventory.QuestionRule rule = inventory.of(TENANT).questions().stream()
                    .filter(q -> q.questionKey().equals("deaNumber"))
                    .findFirst()
                    .orElseThrow();

            assertEquals(List.of("applicantDetails.specialty"), rule.reads());
        }

        @Test
        @DisplayName("a question with no rule is not listed — unlike a step")
        void skipsUnconditionedQuestions() {
            seedRecred();

            // The asymmetry with steps is deliberate. A step with no rule is a decision about the
            // form's flow and worth seeing; an unconditioned question is simply the normal case, and
            // listing all of them would bury the handful that carry logic.
            assertTrue(inventory.of(TENANT).questions().isEmpty());
        }
    }

    @Nested
    @DisplayName("labels")
    class Labels {

        @Test
        @DisplayName("resolves an answer path to the question's label, so a rule reads rather than decodes")
        void resolvesFromTheCatalog() {
            seedRecred();
            questions.with(catalogQuestion("q_specialty", "specialty", "Primary specialty"));
            sections.with(new SectionDefinition(
                    "sd_applicant",
                    TENANT,
                    "applicant",
                    "Applicant Details",
                    null,
                    null,
                    null,
                    List.of(instance("specialty", "q_specialty", null)),
                    true));

            Map<String, String> labels = inventory.of(TENANT).labels();

            // Keyed by the placement-scoped path, because that is what a rule actually names.
            assertEquals("Primary specialty", labels.get("applicantDetails.specialty"));
        }

        @Test
        @DisplayName("an override beats the catalog label, because that is why the override exists")
        void overrideWins() {
            seedRecred();
            questions.with(catalogQuestion("q_specialty", "specialty", "Primary specialty"));
            sections.with(new SectionDefinition(
                    "sd_applicant",
                    TENANT,
                    "applicant",
                    "Applicant Details",
                    null,
                    null,
                    null,
                    List.of(instance("specialty", "q_specialty", "Board specialty (as filed)")),
                    true));

            // Showing the canonical label in a rule about a form that words the question differently
            // would be quietly misleading — the override exists precisely because the payer differs.
            assertEquals(
                    "Board specialty (as filed)", inventory.of(TENANT).labels().get("applicantDetails.specialty"));
        }

        @Test
        @DisplayName("a question whose catalog entry is missing is omitted rather than mislabelled")
        void omitsUnresolvable() {
            seedRecred();
            sections.with(new SectionDefinition(
                    "sd_applicant",
                    TENANT,
                    "applicant",
                    "Applicant Details",
                    null,
                    null,
                    null,
                    List.of(instance("specialty", "q_gone", null)),
                    true));

            // No entry rather than a placeholder: the client falls back to the path, which is at least
            // true. A guessed label on a rules screen is worse than a raw path.
            assertFalse(inventory.of(TENANT).labels().containsKey("applicantDetails.specialty"));
        }
    }

    @Nested
    @DisplayName("summary")
    class SummaryFacts {

        @Test
        @DisplayName("operator usage counts rules, not occurrences")
        void countsRulesPerOperator() {
            seedRecred();
            forms.save(withStepCondition(
                    forms.findById("fd_recred").orElseThrow(),
                    "applicantDetails",
                    new Expression.All(List.of(
                            new Expression.Leaf("a.b", Operator.EQ, "x"),
                            new Expression.Leaf("a.c", Operator.EQ, "y")))));

            Map<String, Integer> usage = inventory.of(TENANT).summary().operatorUsage();

            // Two `eq` leaves in one rule is one rule using `eq`. The question this answers is "what
            // must a migration support", and for that the number of rules is the useful unit.
            assertEquals(1, usage.get("eq"));
            assertEquals(1, usage.get("nin"));
            assertEquals(1, usage.get("in"), "from specialtyExempt");
        }

        @Test
        @DisplayName("another tenant's rules are absent")
        void isolatesTenants() {
            seedRecred();
            forms.save(FormDefinition.draft("fd_other", "tenant_other", "Someone Else", "practitioner")
                    .placeStep(Step.of("secret", "sd_secret", 10)));

            RulesInventory.Inventory result = inventory.of(TENANT);

            assertEquals(1, result.summary().forms());
            assertFalse(
                    result.steps().stream().anyMatch(s -> s.stepKey().equals("secret")),
                    "a rules screen that leaked across tenants would leak payer configuration");
        }

        @Test
        @DisplayName("an empty tenant reports zeroes rather than failing")
        void handlesEmptyTenant() {
            RulesInventory.Inventory result = inventory.of("tenant_nobody");

            assertEquals(0, result.summary().forms());
            assertTrue(result.steps().isEmpty());
            assertTrue(result.summary().operatorUsage().isEmpty());
        }
    }

    // ------------------------------------------------------------------
    // fixture
    // ------------------------------------------------------------------

    private RulesInventory.Inventory seedAndRead() {
        if (forms.findById("fd_recred").isEmpty()) {
            seedRecred();
        }
        return inventory.of(TENANT);
    }

    /** Three steps: one open, one with a leaf rule, one gated through a named condition. */
    private void seedRecred() {
        sections.with(section("sd_applicant", "applicant", "Applicant Details"))
                .with(section("sd_licensure", "licensure", "Licensure"))
                .with(section("sd_dea", "dea", "DEA Registration"));

        forms.save(new FormDefinition(
                "fd_recred",
                TENANT,
                null,
                "Recred",
                "practitioner",
                null,
                null,
                Map.of(
                        "specialtyExempt",
                        new FormDefinition.NamedCondition(
                                "specialtyExempt",
                                "Specialty exempt from DEA requirements",
                                new Expression.Leaf("applicantDetails.specialty", Operator.IN, List.of("DC", "OD")))),
                List.of(
                        Step.of("applicantDetails", "sd_applicant", 10),
                        Step.of("licensure", "sd_licensure", 20)
                                .withVisibleWhen(new Expression.Leaf(
                                        "applicantDetails.providerType", Operator.NIN, List.of("PhD"))),
                        Step.of("deaRegistration", "sd_dea", 30)
                                .withVisibleWhen(new Expression.Not(new Expression.Ref("specialtyExempt")))),
                List.of(),
                FormDefinition.DefinitionStatus.DRAFT));
    }

    private static QuestionInstance questionWithRule(String key, String reads) {
        return question(key, new Expression.Leaf(reads, Operator.EXISTS, null));
    }

    private static QuestionInstance instance(String key, String catalogId, String labelOverride) {
        return new QuestionInstance(
                key,
                QuestionId.of(catalogId),
                Origin.TEMPLATE,
                true,
                10,
                false,
                Layout.FULL,
                labelOverride,
                null,
                null,
                null,
                null,
                null);
    }

    private static com.certifyos.forms.question_catalog.domain.Question catalogQuestion(
            String id, String key, String label) {
        return new com.certifyos.forms.question_catalog.domain.Question(
                QuestionId.of(id),
                null,
                key,
                label,
                null,
                com.certifyos.forms.question_catalog.domain.ResponseType.TEXT,
                null,
                List.of(),
                Map.of(),
                java.util.Set.of(),
                List.of(),
                null,
                com.certifyos.forms.question_catalog.domain.CatalogStatus.ACTIVE,
                java.util.Set.of(),
                "identity");
    }

    private static SectionDefinition section(String id, String key, String name) {
        return new SectionDefinition(id, TENANT, key, name, null, null, null, List.of(), true);
    }

    private static QuestionInstance question(String key, Expression visibleWhen) {
        return new QuestionInstance(
                key,
                QuestionId.of("q_" + key),
                Origin.ADDED,
                true,
                10,
                false,
                Layout.FULL,
                null,
                null,
                visibleWhen,
                null,
                null,
                null);
    }

    private static FormDefinition withCondition(FormDefinition form, String key, String label, Expression expr) {
        return form.withNamedCondition(new FormDefinition.NamedCondition(key, label, expr));
    }

    private static FormDefinition withStepCondition(FormDefinition form, String stepKey, Expression expr) {
        return form.replaceStep(form.step(stepKey).orElseThrow().withVisibleWhen(expr));
    }

    private static RulesInventory.StepRule step(RulesInventory.Inventory inventory, String stepKey) {
        return inventory.steps().stream()
                .filter(s -> s.stepKey().equals(stepKey))
                .findFirst()
                .orElseThrow();
    }

    private static RulesInventory.NamedRule named(RulesInventory.Inventory inventory, String key) {
        return inventory.namedConditions().stream()
                .filter(n -> n.key().equals(key))
                .findFirst()
                .orElseThrow();
    }
}
