package com.chhaya.relay.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** Messages the server sends to a client. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ServerMessage.Registered.class, name = "REGISTERED"),
        @JsonSubTypes.Type(value = ServerMessage.Accepted.class, name = "ACCEPTED"),
        @JsonSubTypes.Type(value = ServerMessage.Rejected.class, name = "REJECTED"),
        @JsonSubTypes.Type(value = ServerMessage.Deliver.class, name = "DELIVER"),
        @JsonSubTypes.Type(value = ServerMessage.AckResult.class, name = "ACK_RESULT"),
        @JsonSubTypes.Type(value = ServerMessage.ErrorReply.class, name = "ERROR")
})
public sealed interface ServerMessage {

    record Registered(String name) implements ServerMessage {}

    record Accepted(String messageId) implements ServerMessage {}

    record Rejected(String messageId, ErrorCode code) implements ServerMessage {}

    record Deliver(String messageId, String from, String body) implements ServerMessage {}

    record AckResult(String messageId, AckStatus status) implements ServerMessage {}

    record ErrorReply(ErrorCode code, String detail) implements ServerMessage {}
}