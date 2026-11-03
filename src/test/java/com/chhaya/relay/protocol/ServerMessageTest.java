package com.chhaya.relay.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ServerMessageTest {

    // No 'default' branch needed: the compiler knows every ServerMessage type.
    // Add a new record later and this switch stops compiling until you handle it.
    private String describe(ServerMessage msg) {
        return switch (msg) {
            case ServerMessage.Registered r -> "registered " + r.name();
            case ServerMessage.Accepted a   -> "accepted " + a.messageId();
            case ServerMessage.Rejected r   -> "rejected " + r.messageId() + " " + r.code();
            case ServerMessage.Deliver d    -> "deliver " + d.messageId() + " from " + d.from();
            case ServerMessage.AckResult a  -> "ack " + a.messageId() + " " + a.status();
            case ServerMessage.ErrorReply e -> "error " + e.code();
        };
    }

    @Test
    void rejectedCarriesTheErrorCode() {
        var msg = new ServerMessage.Rejected("m-1", ErrorCode.MAILBOX_FULL);
        assertEquals("rejected m-1 MAILBOX_FULL", describe(msg));
    }

    @Test
    void staleAckIsReportedAsUnknown() {
        var msg = new ServerMessage.AckResult("m-9", AckStatus.UNKNOWN);
        assertEquals("ack m-9 UNKNOWN", describe(msg));
    }
}