package com.certifyos.forms.form_authoring.infrastructure;

import com.certifyos.forms.form_authoring.domain.port.DomainEventPublisher;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

/**
 * Publishes domain events through CDI.
 *
 * <p>The adapter exists so the domain never imports a container — {@code LayeringTest} enforces
 * that. Swapping this for an outbox writer is a change to one class, because the port's signature
 * says nothing about how delivery happens.
 */
@ApplicationScoped
public class CdiDomainEventPublisher implements DomainEventPublisher {

    private final Event<Object> events;

    @Inject
    public CdiDomainEventPublisher(Event<Object> events) {
        this.events = events;
    }

    @Override
    public void publish(Object event) {
        events.fire(event);
    }
}
