package com.certifyos.forms.form_authoring.domain.compile;

import com.certifyos.forms.form_authoring.domain.definition.FormDefinition;
import com.certifyos.forms.form_authoring.domain.definition.QuestionInstance;
import com.certifyos.forms.form_authoring.domain.definition.SectionDefinition;
import com.certifyos.forms.form_authoring.domain.definition.Step;
import com.certifyos.forms.form_authoring.domain.definition.StepKey;
import com.certifyos.forms.form_authoring.domain.publishing.CompiledForm;
import com.certifyos.forms.question_catalog.domain.OptionSet;
import com.certifyos.forms.question_catalog.domain.Question;
import com.certifyos.forms.question_catalog.domain.ValidationRule;
import com.certifyos.forms.shared_kernel.expression.AnalysisScope;
import com.certifyos.forms.shared_kernel.expression.Expression;
import com.certifyos.forms.shared_kernel.expression.ExpressionAnalyzer;
import com.certifyos.forms.shared_kernel.expression.ExpressionCodec;
import com.certifyos.forms.shared_kernel.expression.Operator;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns an authored {@link FormDefinition} into the runtime artifact.
 *
 * <p><b>A pure function.</b> No I/O, no repositories, no clock — everything it needs arrives as
 * arguments. That is what makes the golden tests meaningful, makes a published version exactly
 * reproducible, and makes this trivially extractable if publishing ever becomes its own service.
 *
 * <p>What it does, in order:
 *
 * <ol>
 *   <li>Resolve each step to its section definition and each question to its catalog entry
 *   <li>Drop everything disabled — those never reach the artifact at all
 *   <li>Inline named conditions, so a published version never changes when one is edited
 *   <li>Analyze every surviving condition and collect problems
 *   <li>Emit one {@code step} per step, in the existing wire format
 * </ol>
 *
 * <p><b>What v0 does not emit, and says so.</b> Hard stops, per-question child inputs and a step's
 * audience rule are all stored faithfully by the model and are not carried into the artifact. They
 * used to be dropped in silence, which is the worst available behaviour: an author could configure a
 * disqualifying hard stop, publish without error, and ship a form that never blocks anyone. Each now
 * produces a {@link CompilationReport.Notice} — publishing still succeeds, because the data is
 * intact and a later version can compile it, but nobody can believe it already works.
 */
public final class FormCompiler {

    private FormCompiler() {}

    /**
     * @throws CompilationFailedException carrying every problem found, not just the first
     */
    public static CompiledForm compile(
            FormDefinition definition, Map<String, SectionDefinition> sections, CatalogSnapshot catalog) {

        Result result = analyze(definition, sections, catalog);
        if (!result.report().isClean()) {
            throw new CompilationFailedException(result.report());
        }
        return result.artifact();
    }

    /** Compile without throwing, for validate and change-preview endpoints. */
    public static Result analyze(
            FormDefinition definition, Map<String, SectionDefinition> sections, CatalogSnapshot catalog) {

        List<CompilationReport.Problem> problems = new ArrayList<>();
        List<CompilationReport.Notice> notices = new ArrayList<>();
        AnalysisScope scope = buildScope(definition, sections, catalog, problems);

        // Form-level: a hard stop is stored but never evaluated, so nothing blocks submission.
        for (FormDefinition.HardStop stop : definition.hardStops()) {
            notices.add(new CompilationReport.Notice(
                    null,
                    null,
                    CompilationReport.Notice.Code.HARD_STOP_NOT_COMPILED,
                    "The rule \"" + stop.message() + "\" is saved but will not block submission yet — "
                            + "this version of the compiler does not emit hard stops."));
        }

        List<Step> steps = definition.orderedSteps();
        List<CompiledForm.CompiledStep> compiledSteps = new ArrayList<>();

        // Which section each step placed. Needed to catch a question rule reaching into another
        // placement of its own section — see reportPlacementHazards.
        Map<String, String> sectionByStepKey = new LinkedHashMap<>();
        steps.forEach(s -> sectionByStepKey.put(s.key().value(), s.sectionDefinitionId()));

        for (int ordinal = 0; ordinal < steps.size(); ordinal++) {
            Step step = steps.get(ordinal);
            SectionDefinition section = sections.get(step.sectionDefinitionId());

            if (section == null) {
                problems.add(new CompilationReport.Problem(
                        step.key().value(),
                        null,
                        ExpressionAnalyzer.Code.DANGLING_PATH,
                        "This step points at a section that no longer exists.",
                        step.sectionDefinitionId()));
                continue;
            }

            // A step's own condition, analyzed at the step's position so a rule depending on a
            // later answer is caught rather than silently never firing.
            for (ExpressionAnalyzer.Finding finding : ExpressionAnalyzer.analyze(step.visibleWhen(), ordinal, scope)) {
                problems.add(CompilationReport.Problem.at(step.key().value(), finding));
            }

            if (step.audienceWhen() != null) {
                notices.add(new CompilationReport.Notice(
                        step.key().value(),
                        null,
                        CompilationReport.Notice.Code.AUDIENCE_RULE_NOT_COMPILED,
                        "This step is restricted to certain users, but that restriction is not applied yet — "
                                + "everyone filling the form will see it."));
            }

            compiledSteps.add(compileStep(step, section, ordinal, scope, catalog, sectionByStepKey, problems, notices));
        }

        CompiledForm artifact = new CompiledForm(definition.name(), null, compiledSteps, null);
        return new Result(artifact, new CompilationReport(problems, notices));
    }

    /** @param artifact valid only when the report is clean */
    public record Result(CompiledForm artifact, CompilationReport report) {}

    // ------------------------------------------------------------------
    // scope
    // ------------------------------------------------------------------

    /**
     * Every answer path in the form, with the ordinal of the step that owns it — which is what
     * makes forward references detectable — plus the values each select question may hold.
     */
    private static AnalysisScope buildScope(
            FormDefinition definition,
            Map<String, SectionDefinition> sections,
            CatalogSnapshot catalog,
            List<CompilationReport.Problem> problems) {

        Map<String, Integer> ordinals = new LinkedHashMap<>();
        Map<String, Set<Object>> allowedValues = new LinkedHashMap<>();
        Set<String> repeatScopes = new LinkedHashSet<>();

        List<Step> steps = definition.orderedSteps();
        for (int ordinal = 0; ordinal < steps.size(); ordinal++) {
            Step step = steps.get(ordinal);
            if (step.isRepeating()) {
                repeatScopes.add(step.key().value());
            }
            SectionDefinition section = sections.get(step.sectionDefinitionId());
            if (section == null) {
                continue;
            }
            for (QuestionInstance instance : section.enabledQuestions()) {
                String path = step.pathFor(instance.key());
                ordinals.put(path, ordinal);

                catalog.question(instance.catalogQuestionId())
                        .flatMap(q -> catalog.optionSet(q.optionSetKey()))
                        .ifPresent(set -> allowedValues.put(path, set.values()));
            }
        }

        return new AnalysisScope(ordinals, allowedValues, repeatScopes, definition.namedConditionExpressions());
    }

    /**
     * Replaces every {@link Expression.Ref} with the condition it names.
     *
     * <p>This is what makes design principle P3 real. Without it a {@code ref} survives into the
     * artifact, which means the renderer would need the form's named conditions alongside it, and —
     * worse — editing a named condition would change the behaviour of an <em>already published</em>
     * version. A provider could sign an attestation and have the rules move underneath them.
     *
     * <p>A cycle is reported by {@link ExpressionAnalyzer}; here it simply stops expanding, so a
     * broken definition produces a report rather than a stack overflow.
     */
    static Expression inline(Expression expression, Map<String, Expression> named, Set<String> visiting) {
        if (expression == null) {
            return null;
        }
        return switch (expression) {
            case Expression.All all -> new Expression.All(
                    all.operands().stream().map(e -> inline(e, named, visiting)).toList());
            case Expression.Any any -> new Expression.Any(
                    any.operands().stream().map(e -> inline(e, named, visiting)).toList());
            case Expression.Not not -> new Expression.Not(inline(not.operand(), named, visiting));
            case Expression.Some some -> new Expression.Some(some.scope(), inline(some.where(), named, visiting));
            case Expression.Every every -> new Expression.Every(every.scope(), inline(every.where(), named, visiting));
            case Expression.Ref ref -> {
                Expression resolved = named.get(ref.key());
                if (resolved == null || visiting.contains(ref.key())) {
                    // Unresolved or cyclic — the analyzer has already recorded it.
                    yield ref;
                }
                Set<String> next = new LinkedHashSet<>(visiting);
                next.add(ref.key());
                yield inline(resolved, named, next);
            }
            case Expression.Leaf leaf -> leaf;
        };
    }

    private static Expression inline(Expression expression, Map<String, Expression> named) {
        return inline(expression, named, Set.of());
    }

    /**
     * Resolves a question rule's bare paths against the step that placed it.
     *
     * <p>A section is reusable, so a rule inside one cannot know the step key it will be placed
     * under — that is chosen at placement time, and the same section may be placed twice. So a rule
     * addresses a sibling by bare key ({@code boardCertified}) and the compiler qualifies it here,
     * per placement, into {@code applicantDetails.boardCertified}.
     *
     * <p>This closes a gap where the two halves of the feature were built on opposite assumptions.
     * {@code SectionDefinition.externalRefs} and its unit tests were written for bare keys; the scope
     * was keyed only by qualified ones, so a bare key compiled to {@code DANGLING_PATH}. The fixtures
     * were then written qualified to satisfy the compiler — which hardcodes a placement key into
     * reusable content, and makes a section placed twice read the first placement's answers in both.
     *
     * <p>Three path shapes are left alone, and each for a different reason:
     *
     * <ul>
     *   <li><b>Already qualified</b> — a genuine cross-section reference, which is legitimate and
     *       common: a question in Licensure gated on an answer in Applicant Details.
     *   <li><b>Context paths</b> ({@code viewer.role}) — not answers, so they have no placement.
     *   <li><b>{@code @item.} paths</b> — already scoped to the current repetition.
     * </ul>
     *
     * <p>A bare path nested inside a {@code some}/{@code every} still qualifies to the owning step
     * rather than to the repetition, because {@code @item.} is how a repetition is addressed. That is
     * a choice rather than a consequence, so it is stated here.
     */
    /**
     * Flags a question rule that names a placement key rather than a sibling.
     *
     * <p>Two shapes, and the severity differs because the consequences do.
     *
     * <p><b>A problem: reaching into another placement of this same section.</b> The address section
     * placed as Practice Location and Billing Address, with a rule inside it reading
     * {@code practiceLocation.line1}, compiles perfectly and gates <em>both</em> copies on the
     * practice location's answer. The billing address's field then appears because of something the
     * provider typed somewhere else — a wrong answer, not an awkward one, and invisible in the
     * artifact unless you read both steps side by side. This is the case that motivated qualifying
     * bare keys in the first place, so it is refused rather than merely noted.
     *
     * <p><b>A notice: naming its own current placement.</b> {@code applicantDetails.boardCertified}
     * inside the section placed as {@code applicantDetails} works today and produces the right
     * artifact. But it has quietly stopped being reusable: place the section under any other key and
     * the rule dangles. Worth telling an author, not worth refusing, since nothing is wrong yet.
     *
     * <p>Both are computed from the resolved expression against the authored one, which is why the
     * caller passes the resolved form: a bare key qualifies to exactly this step, so it can never
     * trip either check.
     */
    private static void reportPlacementHazards(
            Step step,
            QuestionInstance instance,
            Expression resolved,
            Map<String, String> sectionByStepKey,
            List<CompilationReport.Problem> problems,
            List<CompilationReport.Notice> notices) {

        Set<String> authored = literalPaths(instance.visibleWhen());

        for (String path : literalPaths(resolved)) {
            int dot = path.indexOf('.');
            if (dot <= 0 || AnalysisScope.isItemPath(path) || AnalysisScope.isContextPath(path)) {
                continue;
            }
            String referencedStep = path.substring(0, dot);
            if (referencedStep.equals(step.key().value())) {
                // Only a hazard if the author wrote the prefix themselves; a qualified bare key is
                // this compiler's own doing and carries no risk.
                if (authored.contains(path)) {
                    notices.add(new CompilationReport.Notice(
                            step.key().value(),
                            instance.key(),
                            CompilationReport.Notice.Code.PLACEMENT_KEY_IN_QUESTION_RULE,
                            "This question's rule names the step key \"" + referencedStep
                                    + "\" instead of the question alone, so the section only works while it is "
                                    + "placed under that key. Drop the prefix to make it reusable."));
                }
                continue;
            }
            if (step.sectionDefinitionId().equals(sectionByStepKey.get(referencedStep))) {
                problems.add(new CompilationReport.Problem(
                        step.key().value(),
                        instance.key(),
                        ExpressionAnalyzer.Code.CROSS_PLACEMENT_REFERENCE,
                        "This question's rule reads from \"" + referencedStep
                                + "\", which is another placement of this same section — so both copies would be "
                                + "gated on one copy's answer. Drop the prefix to mean this placement's own "
                                + "question.",
                        path));
            }
        }
    }

    private static Expression qualify(Expression expression, StepKey owner) {
        if (expression == null) {
            return null;
        }
        return switch (expression) {
            case Expression.All all -> new Expression.All(
                    all.operands().stream().map(e -> qualify(e, owner)).toList());
            case Expression.Any any -> new Expression.Any(
                    any.operands().stream().map(e -> qualify(e, owner)).toList());
            case Expression.Not not -> new Expression.Not(qualify(not.operand(), owner));
            case Expression.Some some -> new Expression.Some(some.scope(), qualify(some.where(), owner));
            case Expression.Every every -> new Expression.Every(every.scope(), qualify(every.where(), owner));
                // A ref names a form-level condition, which is authored against the whole form and is
                // therefore already qualified. Rewriting inside it would be wrong twice: it is not this
                // section's to interpret, and the same condition may be referenced from several steps.
            case Expression.Ref ref -> ref;
            case Expression.Leaf leaf -> needsQualifying(leaf.path())
                    ? new Expression.Leaf(owner.pathFor(leaf.path()), leaf.operator(), leaf.value())
                    : leaf;
        };
    }

    private static boolean needsQualifying(String path) {
        return path != null
                && !path.isBlank()
                && path.indexOf('.') < 0
                && !AnalysisScope.isItemPath(path)
                && !AnalysisScope.isContextPath(path);
    }

    /** Leaf paths an expression names literally, without following refs. */
    private static Set<String> literalPaths(Expression expression) {
        Set<String> paths = new LinkedHashSet<>();
        collectLiteralPaths(expression, paths);
        return paths;
    }

    private static void collectLiteralPaths(Expression expression, Set<String> paths) {
        if (expression == null) {
            return;
        }
        switch (expression) {
            case Expression.Leaf leaf -> paths.add(leaf.path());
            case Expression.Not not -> collectLiteralPaths(not.operand(), paths);
            case Expression.All all -> all.operands().forEach(e -> collectLiteralPaths(e, paths));
            case Expression.Any any -> any.operands().forEach(e -> collectLiteralPaths(e, paths));
            case Expression.Some some -> collectLiteralPaths(some.where(), paths);
            case Expression.Every every -> collectLiteralPaths(every.where(), paths);
            case Expression.Ref ignored -> {}
        }
    }

    // ------------------------------------------------------------------
    // steps and fields
    // ------------------------------------------------------------------

    private static CompiledForm.CompiledStep compileStep(
            Step step,
            SectionDefinition section,
            int ordinal,
            AnalysisScope scope,
            CatalogSnapshot catalog,
            Map<String, String> sectionByStepKey,
            List<CompilationReport.Problem> problems,
            List<CompilationReport.Notice> notices) {

        List<CompiledForm.CompiledField> fields = new ArrayList<>();

        for (QuestionInstance instance : section.enabledQuestions()) {
            Optional<Question> catalogEntry = catalog.question(instance.catalogQuestionId());
            if (catalogEntry.isEmpty()) {
                problems.add(new CompilationReport.Problem(
                        step.key().value(),
                        instance.key(),
                        ExpressionAnalyzer.Code.DANGLING_PATH,
                        "This question is no longer in the catalog.",
                        instance.catalogQuestionId().value()));
                continue;
            }
            // Bare sibling keys resolve against this placement, so a section placed twice gates
            // each copy on its own answers.
            Expression resolved = qualify(instance.visibleWhen(), step.key());
            reportPlacementHazards(step, instance, resolved, sectionByStepKey, problems, notices);

            for (ExpressionAnalyzer.Finding finding :
                    ExpressionAnalyzer.analyze(resolved, ordinal, scope, ExpressionAnalyzer.Level.QUESTION)) {
                problems.add(CompilationReport.Problem.at(step.key().value(), instance.key(), finding));
            }
            Question question = catalogEntry.get();
            if (!question.children().isEmpty()) {
                notices.add(new CompilationReport.Notice(
                        step.key().value(),
                        instance.key(),
                        CompilationReport.Notice.Code.GROUPED_QUESTION_NOT_COMPILED,
                        "\"" + (instance.labelOverride() != null ? instance.labelOverride() : question.label())
                                + "\" has " + question.children().size()
                                + " sub-question(s) that will not appear — this version of the compiler emits "
                                + "only the parent question."));
            }

            fields.add(compileField(step, instance, resolved, question, catalog, scope.namedConditions()));
        }

        return new CompiledForm.CompiledStep(
                step.key().value(),
                step.titleOverride() != null ? step.titleOverride() : section.name(),
                null,
                fields,
                toJson(inline(step.visibleWhen(), scope.namedConditions())),
                null,
                null);
    }

    private static CompiledForm.CompiledField compileField(
            Step step,
            QuestionInstance instance,
            Expression resolvedVisibleWhen,
            Question question,
            CatalogSnapshot catalog,
            Map<String, Expression> namedConditions) {

        Optional<OptionSet> optionSet = catalog.optionSet(question.optionSetKey());

        return new CompiledForm.CompiledField(
                step.pathFor(instance.key()),
                instance.labelOverride() != null ? instance.labelOverride() : question.label(),
                question.responseType().wireName(),
                instance.required() ? Boolean.TRUE : null,
                instance.helpTextOverride() != null ? instance.helpTextOverride() : question.helpText(),
                optionSet.map(set -> compileOptions(set, question.filteredBy())).orElse(null),
                compileValidation(question.validations()),
                new CompiledForm.CompiledLayout(instance.layout().columns()),
                // Production's own simple-visibility mechanism. Emitted for the "parent is
                // answered" shape so existing forms stay round-trippable; richer rules use
                // `condition` instead.
                dependsOnFor(step, resolvedVisibleWhen),
                toJson(inline(resolvedVisibleWhen, namedConditions)),
                null,
                null,
                null);
    }

    /**
     * PRD §4.3 in the target format: an option carries the parent value it belongs to, and the
     * renderer filters on it. Driven by option tags, so a new filtering rule is a new tag.
     */
    private static List<CompiledForm.CompiledOption> compileOptions(OptionSet set, String filteredBy) {
        return set.options().stream()
                .map(option -> new CompiledForm.CompiledOption(
                        option.value(), option.label(), filteredBy == null ? null : firstTagValue(option, filteredBy)))
                .toList();
    }

    private static String firstTagValue(OptionSet.Option option, String tagKey) {
        List<String> values = option.tags().get(tagKey);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    /**
     * Emits {@code dependsOn} when a condition is exactly "the parent question is answered", which
     * is how every conditional field in the production config is expressed. Anything richer stays
     * in {@code condition}.
     */
    private static String dependsOnFor(Step step, Expression resolvedVisibleWhen) {
        // The resolved expression, not the authored one. Before bare keys were qualified, a rule
        // written against one placement emitted `dependsOn` there and null in the other — the
        // compiler knew the second was cross-step and said nothing about it.
        if (!(resolvedVisibleWhen instanceof Expression.Leaf leaf)) {
            return null;
        }
        if (leaf.operator() != Operator.EXISTS) {
            return null;
        }
        String prefix = step.key().value() + ".";
        return leaf.path().startsWith(prefix) ? leaf.path().substring(prefix.length()) : null;
    }

    private static CompiledForm.CompiledValidation compileValidation(List<ValidationRule> rules) {
        if (rules.isEmpty()) {
            return null;
        }
        Integer minLength = null;
        Integer maxLength = null;
        String pattern = null;
        String customValidator = null;
        String maxDate = null;
        String minDate = null;
        Integer maxSize = null;

        for (ValidationRule rule : rules) {
            switch (rule.rule()) {
                case "minLength" -> minLength = intParam(rule, "value");
                case "maxLength" -> maxLength = intParam(rule, "value");
                case "length" -> {
                    Integer exact = intParam(rule, "exact");
                    minLength = exact;
                    maxLength = exact;
                }
                case "regex" -> pattern = stringParam(rule, "pattern");
                case "npiChecksum" -> customValidator = "npi-luhn";
                case "dateRange" -> {
                    maxDate = stringParam(rule, "max");
                    minDate = stringParam(rule, "min");
                }
                case "fileSize" -> maxSize = intParam(rule, "maxBytes");
                default -> {
                    // required / numeric / phone / fax / tin / email carry no extra wire config.
                }
            }
        }
        return new CompiledForm.CompiledValidation(
                minLength, maxLength, null, null, pattern, null, maxSize, customValidator, maxDate, minDate);
    }

    private static Integer intParam(ValidationRule rule, String key) {
        Object v = rule.params().get(key);
        return v instanceof Number n ? n.intValue() : null;
    }

    private static String stringParam(ValidationRule rule, String key) {
        Object v = rule.params().get(key);
        return v == null ? null : String.valueOf(v);
    }

    // ------------------------------------------------------------------
    // expression to wire JSON — delegated, so persistence and the artifact cannot diverge
    // ------------------------------------------------------------------

    /**
     * Serialises a condition into the artifact.
     *
     * <p><b>This emits the grammar's own spelling — {@code op}, {@code eq}, {@code nin} — and not
     * production's {@code operator}/{@code equals}/{@code notEquals}/{@code contains}. That is a
     * decision, not an oversight.</b>
     *
     * <p>No naming scheme could reconcile the two. Production's {@code StepCondition} is a single
     * flat triple, so a recursive {@code all}/{@code any}/{@code not} is unrepresentable in it — and
     * that recursion is precisely what CP-38192 needs. Renaming the operators would imply a
     * compatibility that the shape already rules out, while costing the shared grammar its single
     * spelling across the FE↔BE conformance suite.
     *
     * <p>Worth knowing before anyone points this artifact at today's renderer:
     * {@code evaluateCondition} ends with {@code default: return true}, so an unrecognised operator
     * makes the step <em>visible</em>. Every gate would silently become permanent rather than
     * failing loudly. Productionising this design means one lowering step here (emit the flat form
     * for rules that fit it) or one replaced function there (swap {@code evaluateCondition} for the
     * shared evaluator) — see the P2 amendment in {@code docs/form-config-poc.md}.
     */
    static JsonNode toJson(Expression expression) {
        return ExpressionCodec.write(expression);
    }
}
