package com.chhaya.relay.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class MailboxTest {

    private static Message msg(String id) {
        return new Message(id, "alice", "bob", "hello " + id);
    }

    @Test
    void ackedMessageIsRemoved() {
        var mailbox = new Mailbox(10);
        mailbox.offer(msg("m-1"));

        List<Message> delivered = mailbox.takeForDelivery();
        boolean acked = mailbox.ack("m-1");

        assertEquals(List.of(msg("m-1")), delivered);
        assertTrue(acked);
        assertEquals(0, mailbox.size());
    }

    @Test
    void messagesForOfflineRecipientAreKeptInOrder() {
        var mailbox = new Mailbox(10);

        // recipient is offline: nobody calls takeForDelivery yet
        mailbox.offer(msg("m-1"));
        mailbox.offer(msg("m-2"));

        // recipient reconnects
        assertEquals(List.of(msg("m-1"), msg("m-2")), mailbox.takeForDelivery());
    }

    @Test
    void unackedMessageIsRedeliveredFirstAfterDisconnect() {
        var mailbox = new Mailbox(10);
        mailbox.offer(msg("m-1"));
        mailbox.takeForDelivery();       // m-1 is now awaiting ack
        mailbox.offer(msg("m-2"));       // arrives while m-1 is unacked

        mailbox.returnAwaitingAck();     // connection drops before the ack

        assertEquals(List.of(msg("m-1"), msg("m-2")), mailbox.takeForDelivery());
    }
}