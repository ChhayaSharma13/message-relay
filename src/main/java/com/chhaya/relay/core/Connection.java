package com.chhaya.relay.core;

/**
 * How the Broker talks to a connected client.
 *
 * The Broker only needs to do two things to a client: give it a message and
 * close it. Keeping that behind this interface means the Broker has no
 * networking code. In the running server, ClientConnection implements it;
 * in tests, a simple fake records what it was given.
 */
public interface Connection {

    /**
     * Gives this client a message to send. This is called on the sender's
     * thread, so it must return quickly: the real implementation queues the
     * message instead of writing it straight to the network.
     */
    void deliver(Message message);

    /** Closes this client's connection, for example when the same name connects from somewhere else. */
    void close();
}