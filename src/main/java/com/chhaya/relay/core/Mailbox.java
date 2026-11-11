package com.chhaya.relay.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds the messages for one user.
 *
 * Each message goes through these steps:
 *   1. waiting  - stored, not sent to the user yet
 *   2. unacked  - sent to the user, waiting for their ACK
 *   3. removed  - the user sent an ACK
 *
 * If the user disconnects before sending an ACK, the unacked messages are
 * moved back to the front of the waiting list, so they are sent again first.
 *
 * All public methods are synchronized, so only one thread can use a mailbox
 * at a time. Different users' mailboxes don't block each other.
 */
class Mailbox {

    private final int maxMessages;
    private final List<Message> waiting = new ArrayList<>();   // not sent yet, oldest first
    private final List<Message> unacked = new ArrayList<>();   // sent, waiting for an ACK, oldest first

    Mailbox(int maxMessages) {
        if (maxMessages <= 0) {
            throw new IllegalArgumentException("maxMessages must be positive: " + maxMessages);
        }
        this.maxMessages = maxMessages;
    }

    synchronized OfferResult add(Message message) {
        // Check for a duplicate first: if a client retries a message we already
        // have, we say "accepted" even if the mailbox has filled up since.
        if (hasMessage(message.id())) {
            return OfferResult.DUPLICATE;
        }
        if (size() >= maxMessages) {
            return OfferResult.FULL;
        }
        waiting.add(message);
        return OfferResult.ADDED;
    }

    /** Returns all waiting messages and marks them as unacked. */
    synchronized List<Message> takeWaitingMessages() {
        List<Message> toSend = new ArrayList<>(waiting);
        unacked.addAll(waiting);
        waiting.clear();
        return toSend;
    }

    /** Returns true if the message was unacked and is now removed. */
    synchronized boolean ack(String messageId) {
        for (int i = 0; i < unacked.size(); i++) {
            if (unacked.get(i).id().equals(messageId)) {
                unacked.remove(i);
                return true;
            }
        }
        return false;
    }

    /** Called when the user disconnects: unacked messages go back to the front, in the same order. */
    synchronized void moveUnackedBackToWaiting() {
        waiting.addAll(0, unacked);
        unacked.clear();
    }

    synchronized int size() {
        return waiting.size() + unacked.size();
    }

    private boolean hasMessage(String messageId) {
        for (Message message : waiting) {
            if (message.id().equals(messageId)) {
                return true;
            }
        }
        for (Message message : unacked) {
            if (message.id().equals(messageId)) {
                return true;
            }
        }
        return false;
    }
}