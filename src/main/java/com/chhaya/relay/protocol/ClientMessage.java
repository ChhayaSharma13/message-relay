package com.chhaya.relay.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** Messages a client sends to the server. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ClientMessage.Register.class, name = "REGISTER"),
        @JsonSubTypes.Type(value = ClientMessage.Send.class, name = "SEND"),
        @JsonSubTypes.Type(value = ClientMessage.Ack.class, name = "ACK")
})
public sealed interface ClientMessage {

    record Register(String name) implements ClientMessage {}

    record Send(String messageId, String to, String body) implements ClientMessage {}

    record Ack(String messageId) implements ClientMessage {}
}