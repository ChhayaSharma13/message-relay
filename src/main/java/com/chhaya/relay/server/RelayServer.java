package com.chhaya.relay.server;

import com.chhaya.relay.core.Broker;
import com.chhaya.relay.protocol.ErrorCode;
import com.chhaya.relay.protocol.ServerMessage;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Listens for TCP connections and gives each client its own ClientConnection.
 * Each client runs on its own virtual thread, so one client never blocks another.
 */
public class RelayServer {

    private static final System.Logger log = System.getLogger(RelayServer.class.getName());

    private final int port;
    private final Broker broker;
    private final FrameCodec codec;
    private final int maxConnections;
    private final int idleTimeoutMillis;

    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<ClientConnection> clients = ConcurrentHashMap.newKeySet();
    private ServerSocket serverSocket;
    private volatile boolean running;

    public RelayServer(int port, Broker broker, FrameCodec codec, int maxConnections, int idleTimeoutMillis) {
        this.port = port;
        this.broker = broker;
        this.codec = codec;
        this.maxConnections = maxConnections;
        this.idleTimeoutMillis = idleTimeoutMillis;
    }

    /** Starts listening. Returns straight away; connections are handled in the background. */
    public void start() throws IOException {
        serverSocket = new ServerSocket(port);   // port 0 = let the computer pick a free port (used in tests)
        running = true;
        threads.submit(this::acceptLoop);
        log.log(Level.INFO, "Relay listening on port {0}", String.valueOf(getPort()));
    }

    public int getPort() {
        return serverSocket.getLocalPort();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();   // waits for the next client
                if (clients.size() >= maxConnections) {
                    rejectBecauseBusy(socket);
                    continue;
                }
                ClientConnection client = new ClientConnection(socket, broker, codec, idleTimeoutMillis);
                clients.add(client);
                threads.submit(() -> {
                    try {
                        client.run();
                    } finally {
                        clients.remove(client);
                    }
                });
            } catch (IOException e) {
                if (running) {
                    log.log(Level.WARNING, "Could not accept a connection", e);
                }
            }
        }
    }

    private void rejectBecauseBusy(Socket socket) {
        try (socket) {
            codec.writeServerMessage(socket.getOutputStream(),
                    new ServerMessage.ErrorReply(ErrorCode.SERVER_BUSY, "too many connections"));
        } catch (IOException ignored) {
            // the client went away; nothing to do
        }
    }

    /** Stops accepting, closes every client, and waits briefly for threads to finish. */
    public void stop() {
        running = false;
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // already closed
        }
        for (ClientConnection client : clients) {
            client.close();
        }
        threads.shutdownNow();
        try {
            threads.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.log(Level.INFO, "Relay stopped");
    }
}