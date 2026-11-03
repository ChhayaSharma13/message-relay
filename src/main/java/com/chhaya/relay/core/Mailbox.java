package com.chhaya.relay.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One recipient's messages. A message is QUEUED until delivered,
 * then AWAITING ACK
 * until acknowledged, then removed. On disconnect, messages awaiting ack return to
 * the front of the queue so they are redelivered first (at-least-once, FIFO).
 *
 * Thread-safe: every method holds this mailbox's own lock. No method ever holds
 * two locks, so deadlock is impossible.
 */
final class Mailbox {

    private final int capacity;
    private final Deque<Message> queued = new ArrayDeque<>();
    private final Map<String, Message> awaitingAck = new LinkedHashMap<>(); // keeps delivery order
    private final ReentrantLock lock = new ReentrantLock();

    Mailbox(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
    }

    /** Stores a message. Duplicate check comes before the capacity check, so a retry is accepted even when full. */
    OfferResult offer(Message message) {
        lock.lock();
        try {
            if (contains(message.id())) {
                return OfferResult.DUPLICATE;
            }
            if (queued.size() + awaitingAck.size() >= capacity) {
                return OfferResult.FULL;
            }
            queued.addLast(message);
            return OfferResult.ADDED;
        } finally {
            lock.unlock();
        }
    }

    /** Moves every queued message to awaiting ack and returns them, oldest first. */
    List<Message> takeForDelivery() {
        lock.lock();
        try {
            List<Message> batch = new ArrayList<>(queued);
            queued.clear();
            for (Message m : batch) {
                awaitingAck.put(m.id(), m);
            }
            return batch;
        } finally {
            lock.unlock();
        }
    }

    /** Removes a message awaiting ack. Returns false for unknown or already-acked ids (harmless). */
    boolean ack(String messageId) {
        lock.lock();
        try {
            return awaitingAck.remove(messageId) != null;
        } finally {
            lock.unlock();
        }
    }

    /** Called when the recipient disconnects: unacked messages go back to the front, in their original order. */
    void returnAwaitingAck() {
        lock.lock();
        try {
            List<Message> unacked = new ArrayList<>(awaitingAck.values());
            for (int i = unacked.size() - 1; i >= 0; i--) {   // reverse, so addFirst keeps original order
                queued.addFirst(unacked.get(i));
            }
            awaitingAck.clear();
        } finally {
            lock.unlock();
        }
    }

    int size() {
        lock.lock();
        try {
            return queued.size() + awaitingAck.size();
        } finally {
            lock.unlock();
        }
    }

    // Caller must hold the lock.
    private boolean contains(String messageId) {
        if (awaitingAck.containsKey(messageId)) {
            return true;
        }
        for (Message m : queued) {
            if (m.id().equals(messageId)) {
                return true;
            }
        }
        return false;
    }
}