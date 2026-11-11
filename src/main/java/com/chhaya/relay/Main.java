package com.chhaya.relay;

import com.chhaya.relay.core.Broker;
import com.chhaya.relay.server.FrameCodec;
import com.chhaya.relay.server.RelayServer;

/** Starts the relay. */
public class Main {

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9000;

        Broker broker = new Broker(100, 4096);   // max messages per mailbox, max message length
        RelayServer server = new RelayServer(port, broker, new FrameCodec(8192), 100, 300_000);
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));

        Thread.currentThread().join();   // keep the program running
    }
}