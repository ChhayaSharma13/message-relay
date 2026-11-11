package com.chhaya.relay.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.chhaya.relay.core.Broker;
import com.chhaya.relay.protocol.AckStatus;
import com.chhaya.relay.protocol.ClientMessage;
import com.chhaya.relay.protocol.ServerMessage;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class RelayServerTest {

    private final FrameCodec codec = new FrameCodec(8192);
    private RelayServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = new RelayServer(0, new Broker(10, 1000), codec, 10, 5000);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    @Test
    @Timeout(5)   // the test fails instead of hanging if something goes wrong
    void aliceSendsAMessageAndBobReceivesAndAcknowledgesIt() throws IOException {
        try (TestClient alice = new TestClient(); TestClient bob = new TestClient()) {
            bob.send(new ClientMessage.Register("bob"));
            assertEquals(new ServerMessage.Registered("bob"), bob.receive());

            alice.send(new ClientMessage.Register("alice"));
            assertEquals(new ServerMessage.Registered("alice"), alice.receive());

            alice.send(new ClientMessage.Send("m-1", "bob", "hi"));
            assertEquals(new ServerMessage.Accepted("m-1"), alice.receive());
            assertEquals(new ServerMessage.Deliver("m-1", "alice", "hi"), bob.receive());

            bob.send(new ClientMessage.Ack("m-1"));
            assertEquals(new ServerMessage.AckResult("m-1", AckStatus.ACKED), bob.receive());
        }
    }

    // A tiny client that talks to the server over a real TCP connection.
    private class TestClient implements AutoCloseable {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;

        TestClient() throws IOException {
            socket = new Socket("localhost", server.getPort());
            socket.setSoTimeout(2000);   // never wait more than 2 seconds for a reply
            in = new BufferedInputStream(socket.getInputStream());
            out = socket.getOutputStream();
        }

        void send(ClientMessage message) throws IOException {
            codec.writeClientMessage(out, message);
        }

        ServerMessage receive() throws IOException {
            return codec.readServerMessage(in);
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}