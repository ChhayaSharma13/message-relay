package com.chhaya.relay.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps track of users, their mailboxes and who is online.
 *
 * Mailboxes are kept even when a user goes offline, so when they register
 * again with the same name they get their messages back.
 *
 * The Broker knows nothing about sockets. The server calls it.
 */
public class Broker {

    private final int maxMessagesPerMailbox;
    private final int maxMessageLength;

    // One mailbox per user name. Kept even when the user is offline.
    private final Map<String, Mailbox> mailboxes = new ConcurrentHashMap<>();

    // The current connection of each user who is online right now.
    private final Map<String, Connection> onlineUsers = new ConcurrentHashMap<>();

    public Broker(int maxMessagesPerMailbox, int maxMessageLength) {
        this.maxMessagesPerMailbox = maxMessagesPerMailbox;
        this.maxMessageLength = maxMessageLength;
    }

    public void register(String name, Connection connection) {
        // Create a mailbox the first time we see this name.
        Mailbox mailbox = mailboxes.computeIfAbsent(name, key -> new Mailbox(maxMessagesPerMailbox));

        Connection oldConnection = onlineUsers.put(name, connection);
        if (oldConnection != null && oldConnection != connection) {
            // The same user connected again from somewhere else: close the old connection.
            oldConnection.close();
        }

        synchronized (mailbox) {
            // Anything sent before but never acknowledged gets sent again.
            mailbox.moveUnackedBackToWaiting();
            sendWaitingMessages(name, mailbox);
        }
    }

    public SendResult send(String from, String messageId, String to, String body) {
        if (body.length() > maxMessageLength) {
            return SendResult.MESSAGE_TOO_LARGE;
        }

        Mailbox mailbox = mailboxes.get(to);
        if (mailbox == null) {
            return SendResult.UNKNOWN_RECIPIENT;
        }

        synchronized (mailbox) {
            OfferResult result = mailbox.add(new Message(messageId, from, to, body));
            if (result == OfferResult.FULL) {
                return SendResult.MAILBOX_FULL;
            }
            // ADDED or DUPLICATE: either way we have the message.
            sendWaitingMessages(to, mailbox);
        }
        return SendResult.ACCEPTED;
    }

    /** Returns true if the message was in this user's mailbox and is now removed. */
    public boolean ack(String name, String messageId) {
        Mailbox mailbox = mailboxes.get(name);
        if (mailbox == null) {
            return false;
        }
        return mailbox.ack(messageId);
    }

    public void disconnect(String name, Connection connection) {
        // Only remove the user if this is still their current connection.
        // (If they already reconnected, an old connection closing late must not log them out.)
        boolean wasCurrent = onlineUsers.remove(name, connection);
        if (wasCurrent) {
            mailboxes.get(name).moveUnackedBackToWaiting();
        }
    }

    // Call this while holding the mailbox lock, so messages go out in order.
    private void sendWaitingMessages(String name, Mailbox mailbox) {
        Connection connection = onlineUsers.get(name);
        if (connection == null) {
            return; // user is offline: messages stay in the mailbox
        }
        for (Message message : mailbox.takeWaitingMessages()) {
            connection.deliver(message);
        }
    }
}