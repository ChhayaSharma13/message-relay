package com.chhaya.relay.protocol;

/** Messages a client sends to the server. */
public sealed interface ClientMessage {

    record Register(String name) implements ClientMessage {}

    record Send(String messageId, String to, String body) implements ClientMessage {}

    record Ack(String messageId) implements ClientMessage {}
}