package com.chhaya.relay.core;

/**
 * A message held by the relay. Separate from the wire records so the
 * storage model can change without changing the protocol.
 */
public record Message(String id, String from, String to, String body) {}