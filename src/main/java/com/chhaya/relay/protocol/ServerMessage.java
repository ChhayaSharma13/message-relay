package com.chhaya.relay.protocol;

/** Messages the server sends to a client. */
public sealed interface ServerMessage {

    record Registered(String name) implements ServerMessage {}

    record Accepted(String messageId) implements ServerMessage {}

    record Rejected(String messageId, ErrorCode code) implements ServerMessage {}

    record Deliver(String messageId, String from, String body) implements ServerMessage {}

    record AckResult(String messageId, AckStatus status) implements ServerMessage {}

    record ErrorReply(ErrorCode code, String detail) implements ServerMessage {}
}