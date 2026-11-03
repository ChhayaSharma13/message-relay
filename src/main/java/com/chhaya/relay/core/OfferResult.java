package com.chhaya.relay.core;

/** Outcome of adding a message to a mailbox. Returned, not thrown: these are expected outcomes. */
public enum OfferResult {
    ADDED,      // stored for delivery
    DUPLICATE,  // same id already stored: accepted again, not stored twice (idempotent)
    FULL        // mailbox at capacity: rejected (backpressure)
}