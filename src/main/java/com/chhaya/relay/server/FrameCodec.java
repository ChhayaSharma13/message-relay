package com.chhaya.relay.server;

import com.chhaya.relay.protocol.ClientMessage;
import com.chhaya.relay.protocol.ServerMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Reads and writes messages on a TCP connection.
 *
 * Each message is one line of JSON, ending with a newline:
 *   {"type":"SEND","messageId":"m-1","to":"bob","body":"hi"}
 *
 * TCP is just a stream of bytes, so the newline tells the reader where each
 * message ends. JSON never contains a real newline (a newline inside text is
 * written as the two characters \n), so it is safe to use as the separator.
 */
public class FrameCodec {

    private final ObjectMapper json = new ObjectMapper();
    private final int maxLineBytes;

    public FrameCodec(int maxLineBytes) {
        this.maxLineBytes = maxLineBytes;
    }

    // Used by the server.

    public ClientMessage readClientMessage(InputStream in) throws IOException {
        return json.readValue(readLine(in), ClientMessage.class);
    }

    public void writeServerMessage(OutputStream out, ServerMessage message) throws IOException {
        writeLine(out, json.writeValueAsBytes(message));
    }

    // Used by a client (our tests act as clients).

    public ServerMessage readServerMessage(InputStream in) throws IOException {
        return json.readValue(readLine(in), ServerMessage.class);
    }

    public void writeClientMessage(OutputStream out, ClientMessage message) throws IOException {
        writeLine(out, json.writeValueAsBytes(message));
    }

    private byte[] readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            int b = in.read();          // read one byte
            if (b == -1) {
                throw new EOFException("Connection closed");
            }
            if (b == '\n') {
                return line.toByteArray();   // end of the message
            }
            line.write(b);
            // We don't know the size in advance, so we count as we go.
            // This stops a client from sending an endless line and using up memory.
            if (line.size() > maxLineBytes) {
                throw new IOException("Message is longer than " + maxLineBytes + " bytes");
            }
        }
    }

    private void writeLine(OutputStream out, byte[] bytes) throws IOException {
        out.write(bytes);
        out.write('\n');
        out.flush();   // send it now, don't wait for more data
    }
}