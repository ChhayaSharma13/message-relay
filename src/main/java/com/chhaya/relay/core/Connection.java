package com.chhaya.relay.core;

/**
 * A connected client, as the Broker sees it.
 * The server will provide the real one; tests use a fake one.
 */
public interface Connection {

    /** Send a message to this client. Should not block: queue it and return. */
    void deliver(Message message);

    /** Close this connection. */
    void close();
}