package com.chhaya.relay.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ClientMessageTest {

    @Test
    void sendRecordHoldsItsFields() {
        var send = new ClientMessage.Send("m-1", "bob", "hello");

        assertEquals("m-1", send.messageId());
        assertEquals("bob", send.to());
        assertEquals("hello", send.body());
    }
}