package com.certifyos.forms.form_authoring.interfaces;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Set;

/**
 * Swaps the Mongo repositories for in-memory ones.
 *
 * <p>Lets the HTTP layer be tested end to end — routing, serialisation, status codes, exception
 * mappers — with no Mongo and no Docker. Everything above persistence is the real thing; only the
 * store is substituted.
 *
 * <p>The alternative would be Testcontainers, which was deliberately left out of scope: it needs
 * Docker locally and in CI, and it would slow the suite down to verify a layer whose correctness
 * the mapper round-trip tests already pin.
 */
public class RestTestProfile implements QuarkusTestProfile {

    @Override
    public Set<Class<?>> getEnabledAlternatives() {
        return Set.of(
                TestRepositories.Forms.class,
                TestRepositories.Sections.class,
                TestRepositories.Versions.class,
                TestRepositories.Questions.class,
                TestRepositories.Categories.class,
                TestRepositories.OptionSets.class,
                TestRepositories.Templates.class,
                TestRepositories.Blueprints.class);
    }
}
