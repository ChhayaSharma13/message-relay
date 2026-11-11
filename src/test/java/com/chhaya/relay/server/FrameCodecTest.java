package com.chhaya.relay.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chhaya.relay.protocol.ClientMessage;
import com.chhaya.relay.protocol.ServerMessage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FrameCodecTest {

    private final FrameCodec codec = new FrameCodec(1000);

    @Test
    void messageComesBackTheSameAfterWritingAndReading() throws IOException {
        ClientMessage sent = new ClientMessage.Send("m-1", "bob", "hi");

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        codec.writeClientMessage(buffer, sent);
        ByteArrayInputStream in = new ByteArrayInputStream(buffer.toByteArray());

        assertEquals(sent, codec.readClientMessage(in));
    }

    @Test
    void messageIsOneLineOfJsonWithAType() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        codec.writeServerMessage(buffer, new ServerMessage.Deliver("m-1", "alice", "hi"));

        String written = buffer.toString(StandardCharsets.UTF_8);

        assertTrue(written.contains("\"type\":\"DELIVER\""));
        assertTrue(written.endsWith("\n"));
    }

    @Test
    void lineThatIsTooLongIsRejected() {
        byte[] tooLong = new byte[2000];   // 2000 bytes and no newline, over the 1000 limit
        ByteArrayInputStream in = new ByteArrayInputStream(tooLong);

        assertThrows(IOException.class, () -> codec.readClientMessage(in));
    }
}