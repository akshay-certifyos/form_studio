package com.certifyos.forms.shared_kernel.security;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Who is acting.
 *
 * <p><b>Stubbed for the POC.</b> Auth is explicitly out of scope, but the concept is not: a
 * published version records who published it, and that is an audit field rather than decoration. So
 * the seam exists and returns a fixed actor — wiring real identity later changes this class and
 * nothing else.
 *
 * <p>Threading an actor through from the start also keeps it out of the domain: aggregates take an
 * actor as a parameter and never reach for ambient state.
 */
@ApplicationScoped
public class UserContext {

    private static final String POC_ACTOR = "poc-author";

    public String actor() {
        return POC_ACTOR;
    }

    /** Non-answer context for expression evaluation — see {@code EvaluationContext}. */
    public String role() {
        return "admin";
    }
}
