package com.certifyos.forms.form_authoring.domain.port;

/**
 * Publishes a domain event.
 *
 * <p>A port so the domain never imports CDI. In-process today; the same signature moves to an
 * outbox without either side noticing.
 */
public interface DomainEventPublisher {

    void publish(Object event);
}
