package com.chhaya.relay.server;

import com.chhaya.relay.core.Broker;
import com.chhaya.relay.core.Connection;
import com.chhaya.relay.core.Message;
import com.chhaya.relay.core.SendResult;
import com.chhaya.relay.protocol.AckStatus;
import com.chhaya.relay.protocol.ClientMessage;
import com.chhaya.relay.protocol.ErrorCode;
import com.chhaya.relay.protocol.ServerMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger.Level;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * One connected client.
 * The reader (run) reads one message at a time and asks the Broker what to do.
 * All replies go into the outbox queue. A separate writer thread takes them
 * from the queue and sends them, so only one thread ever writes to the socket,
 * and a slow client only slows down its own writer.
 */
class ClientConnection implements Connection {

    private static final System.Logger log = System.getLogger(ClientConnection.class.getName());
    private static final int OUTBOX_SIZE = 100;   // messages waiting to be written to this client

    private final Socket socket;
    private final Broker broker;
    private final FrameCodec codec;
    private final int idleTimeoutMillis;
    private final BlockingQueue<ServerMessage> outbox = new ArrayBlockingQueue<>(OUTBOX_SIZE);

    private String name;               // null until the client registers
    private volatile boolean closed;   // volatile: read and written by different threads

    ClientConnection(Socket socket, Broker broker, FrameCodec codec, int idleTimeoutMillis) {
        this.socket = socket;
        this.broker = broker;
        this.codec = codec;
        this.idleTimeoutMillis = idleTimeoutMillis;
    }

    /** Reads messages until the client disconnects. Runs on its own thread. */
    void run() {
        Thread writer = Thread.ofVirtual().start(this::writeLoop);
        try {
            socket.setSoTimeout(idleTimeoutMillis);   // stop waiting if the client goes quiet
            InputStream in = new BufferedInputStream(socket.getInputStream());

            while (!closed) {
                ClientMessage message;
                try {
                    message = codec.readClientMessage(in);
                } catch (JsonProcessingException e) {
                    // The line was read but isn't a valid message. Tell the client and keep going.
                    reply(new ServerMessage.ErrorReply(ErrorCode.INVALID_REQUEST, "Not a valid message"));
                    continue;
                }
                handle(message);
            }
        } catch (SocketTimeoutException e) {
            log.log(Level.INFO, "Client {0} was idle too long, closing", name);
        } catch (IOException e) {
            log.log(Level.INFO, "Client {0} disconnected: {1}", name, e.getMessage());
        } catch (RuntimeException e) {
            log.log(Level.ERROR, "Unexpected error for client " + name, e);   // a bug: log it, keep the server running
        } finally {
            close();
            writer.interrupt();
            if (name != null) {
                broker.disconnect(name, this);   // unacked messages go back to the mailbox
            }
        }
    }

    private void handle(ClientMessage message) {
        switch (message) {
            case ClientMessage.Register register -> handleRegister(register);
            case ClientMessage.Send send -> handleSend(send);
            case ClientMessage.Ack ack -> handleAck(ack);
        }
    }

    private void handleRegister(ClientMessage.Register register) {
        if (register.name() == null || register.name().isBlank()) {
            reply(new ServerMessage.ErrorReply(ErrorCode.INVALID_REQUEST, "name is required"));
            return;
        }
        if (name != null) {
            reply(new ServerMessage.ErrorReply(ErrorCode.INVALID_REQUEST, "already registered"));
            return;
        }
        name = register.name();
        // Reply first, so REGISTERED arrives before any offline messages the Broker delivers.
        reply(new ServerMessage.Registered(name));
        broker.register(name, this);
        log.log(Level.INFO, "Client registered as {0}", name);
    }

    private void handleSend(ClientMessage.Send send) {
        if (name == null) {
            reply(new ServerMessage.ErrorReply(ErrorCode.NOT_REGISTERED, "register first"));
            return;
        }
        if (send.messageId() == null || send.to() == null || send.body() == null) {
            reply(new ServerMessage.Rejected(send.messageId(), ErrorCode.INVALID_REQUEST));
            return;
        }

        SendResult result = broker.send(name, send.messageId(), send.to(), send.body());
        switch (result) {
            case ACCEPTED -> reply(new ServerMessage.Accepted(send.messageId()));
            case UNKNOWN_RECIPIENT -> reply(new ServerMessage.Rejected(send.messageId(), ErrorCode.UNKNOWN_RECIPIENT));
            case MAILBOX_FULL -> reply(new ServerMessage.Rejected(send.messageId(), ErrorCode.MAILBOX_FULL));
            case MESSAGE_TOO_LARGE -> reply(new ServerMessage.Rejected(send.messageId(), ErrorCode.MESSAGE_TOO_LARGE));
        }
    }

    private void handleAck(ClientMessage.Ack ack) {
        if (name == null) {
            reply(new ServerMessage.ErrorReply(ErrorCode.NOT_REGISTERED, "register first"));
            return;
        }
        boolean acked = broker.ack(name, ack.messageId());
        reply(new ServerMessage.AckResult(ack.messageId(), acked ? AckStatus.ACKED : AckStatus.UNKNOWN));
    }

    /** Called by the Broker when a message arrives for this client. Never blocks. */
    @Override
    public void deliver(Message message) {
        reply(new ServerMessage.Deliver(message.id(), message.from(), message.body()));
    }

    @Override
    public void close() {
        closed = true;
        try {
            socket.close();   // also wakes up the reader if it's waiting
        } catch (IOException ignored) {
            // already closed
        }
    }

    // Puts a message in the outbox. If the outbox is full, the client isn't reading: disconnect it.
    private void reply(ServerMessage message) {
        if (!outbox.offer(message)) {
            log.log(Level.WARNING, "Client {0} is too slow, disconnecting", name);
            close();
        }
    }

    // Runs on its own thread: takes messages from the outbox and writes them to the socket.
    private void writeLoop() {
        try {
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            while (!closed) {
                ServerMessage message = outbox.take();   // waits until there is something to send
                codec.writeServerMessage(out, message);
            }
        } catch (InterruptedException e) {
            // the connection is closing
        } catch (IOException e) {
            close();
        }
    }
}