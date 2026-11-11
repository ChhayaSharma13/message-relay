package com.chhaya.relay.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BrokerTest {

    // A fake connection that just remembers what it was sent.
    private static class FakeConnection implements Connection {
        List<Message> received = new ArrayList<>();
        boolean closed = false;

        @Override
        public void deliver(Message message) {
            received.add(message);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private final Broker broker = new Broker(10, 1000);

    @Test
    void sendToUnknownUserIsRejected() {
        assertEquals(SendResult.UNKNOWN_RECIPIENT, broker.send("alice", "m-1", "nobody", "hi"));
    }

    @Test
    void onlineUserReceivesMessageStraightAway() {
        FakeConnection bob = new FakeConnection();
        broker.register("bob", bob);

        broker.send("alice", "m-1", "bob", "hi");

        assertEquals(1, bob.received.size());
        assertEquals("hi", bob.received.get(0).body());
    }

    @Test
    void offlineUserGetsMessageWhenTheyRegisterAgain() {
        FakeConnection bobFirst = new FakeConnection();
        broker.register("bob", bobFirst);
        broker.disconnect("bob", bobFirst);

        broker.send("alice", "m-1", "bob", "hi");

        FakeConnection bobAgain = new FakeConnection();
        broker.register("bob", bobAgain);
        assertEquals(1, bobAgain.received.size());
    }

    @Test
    void unackedMessageIsSentAgainAfterReconnect() {
        FakeConnection bobFirst = new FakeConnection();
        broker.register("bob", bobFirst);
        broker.send("alice", "m-1", "bob", "hi");   // delivered, but bob never acks
        broker.disconnect("bob", bobFirst);

        FakeConnection bobAgain = new FakeConnection();
        broker.register("bob", bobAgain);

        assertEquals("m-1", bobAgain.received.get(0).id());
    }

    @Test
    void onlyTheRecipientCanAcknowledge() {
        broker.register("bob", new FakeConnection());
        broker.send("alice", "m-1", "bob", "hi");

        assertFalse(broker.ack("alice", "m-1"));   // not alice's message
        assertTrue(broker.ack("bob", "m-1"));
    }
}