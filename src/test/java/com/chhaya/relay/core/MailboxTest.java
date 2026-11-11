package com.chhaya.relay.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class MailboxTest {

    private Message message(String id) {
        return new Message(id, "alice", "bob", "hello " + id);
    }

    @Test
    void ackedMessageIsRemoved() {
        Mailbox mailbox = new Mailbox(10);
        mailbox.add(message("m-1"));

        mailbox.takeWaitingMessages();
        boolean acked = mailbox.ack("m-1");

        assertTrue(acked);
        assertEquals(0, mailbox.size());
    }

    @Test
    void messagesForOfflineUserAreKeptInOrder() {
        Mailbox mailbox = new Mailbox(10);
        mailbox.add(message("m-1"));
        mailbox.add(message("m-2"));

        assertEquals(List.of(message("m-1"), message("m-2")), mailbox.takeWaitingMessages());
    }

    @Test
    void unackedMessageIsSentAgainFirstAfterDisconnect() {
        Mailbox mailbox = new Mailbox(10);
        mailbox.add(message("m-1"));
        mailbox.takeWaitingMessages();   // m-1 sent, not acked
        mailbox.add(message("m-2"));     // m-2 arrives meanwhile

        mailbox.moveUnackedBackToWaiting();   // user disconnected

        assertEquals(List.of(message("m-1"), message("m-2")), mailbox.takeWaitingMessages());
    }

    @Test
    void fullMailboxRejectsNewMessages() {
        Mailbox mailbox = new Mailbox(1);
        mailbox.add(message("m-1"));

        assertEquals(OfferResult.FULL, mailbox.add(message("m-2")));
    }

    @Test
    void duplicateMessageIsNotStoredTwice() {
        Mailbox mailbox = new Mailbox(10);
        mailbox.add(message("m-1"));

        assertEquals(OfferResult.DUPLICATE, mailbox.add(message("m-1")));
        assertEquals(1, mailbox.size());
    }
}