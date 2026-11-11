# Message Relay

A small message relay server written in Java 21.

Clients connect over TCP, register a name, and send messages to each other
through the server. Each message must be acknowledged by the person who
receives it. If the receiver is offline, the server keeps the message and
delivers it when they come back. If a message was delivered but never
acknowledged, it is delivered again.

The design, protocol and trade-offs are explained in [APPROACH.md](APPROACH.md).

## What you need

- **Java 21 or newer.** The code uses virtual threads and pattern-matching
  `switch`, which need Java 21. Check with `java -version`.
- **Maven 3.9 or newer.** Check with `mvn -version`.

Or, to run it without installing Java, see [Run with Docker](#run-with-docker-no-local-java-needed).

## Build

```
mvn clean package
```

This runs all the tests first, then builds one runnable file:
**`target/relay.jar`**. The jar contains the app and its one library
(Jackson, for JSON), so nothing else is needed to run it.

## Run

```
java -jar target/relay.jar
```

You should see:

```
INFO: Relay listening on port 9000
```

To use a different port, pass it as the first argument:

```
java -jar target/relay.jar 9100
```

The default port is **9000**

Press **Ctrl+C** to stop. The server closes every connection cleanly before
exiting.

### Run with Docker (no local Java needed)

```
docker build -t message-relay .
docker run --rm -it -p 9000:9000 message-relay
```

The image is built in two stages: a Maven image builds and tests the jar, then
only the jar is copied into a small Java 21 runtime image. GitHub Actions also
builds the image on every push.

## Run the tests

```
mvn test
```

All tests finish in a few seconds. The network tests pick a free port by
themselves and have timeouts, so they never clash with a running server and
never hang.

## Try it yourself

Messages are one line of JSON each, so you can talk to the server by typing
into `nc` (netcat, which comes with macOS and most Linux systems).

1. Start the server: `java -jar target/relay.jar`

2. **Terminal 1, Bob:**
   ```
   nc localhost 9000
   {"type":"REGISTER","name":"bob"}
   ```
   Bob gets: `{"type":"REGISTERED","name":"bob"}`

3. **Terminal 2, Alice:**
   ```
   nc localhost 9000
   {"type":"REGISTER","name":"alice"}
   {"type":"SEND","messageId":"m-1","to":"bob","body":"hi"}
   ```
   Alice gets: `{"type":"ACCEPTED","messageId":"m-1"}`

4. **Bob's terminal** shows the message straight away:
   ```
   {"type":"DELIVER","messageId":"m-1","from":"alice","body":"hi"}
   ```

5. **Bob acknowledges it:**
   ```
   {"type":"ACK","messageId":"m-1"}
   ```
   Bob gets: `{"type":"ACK_RESULT","messageId":"m-1","status":"ACKED"}`

**Try offline delivery:** press Ctrl+C in Bob's terminal, send another message
from Alice, then reconnect as Bob and register again. The message arrives as
soon as he registers.

## Limits

The server applies limits to individual messages, mailboxes, connections and
outgoing queues to help control resource consumption. It does not yet limit
the total number of registered names (see Known limitations in
[APPROACH.md](APPROACH.md)).

| What | Limit | What happens when it's reached |
|---|---|---|
| Messages stored per person | 100 | New sends are rejected with `MAILBOX_FULL` |
| Message text (`body`) | 4096 characters | Rejected with `MESSAGE_TOO_LARGE` |
| One line of JSON | 8192 bytes | The connection is closed |
| Clients connected at once | 100 | New clients get `SERVER_BUSY` and are closed |
| Replies waiting to be sent to one client | 100 | That client is too slow, so it is disconnected |
| Time a client can stay silent | 5 minutes | The connection is closed |

The limits are set in `Main.java`.

## Project layout

```
src/main/java/com/chhaya/relay/
  Main.java          starts the server
  protocol/          the messages that travel between client and server
  core/              the rules: Broker and Mailbox (no networking here)
  server/            TCP: accepting connections, reading and writing messages
src/test/java/...    tests for each part, plus end-to-end tests over real TCP
Dockerfile           two-stage image build
```

## Continuous integration

On every push, GitHub Actions (`.github/workflows/build.yml`) builds the
project on a clean Linux machine with Java 21, runs all the tests, saves
`relay.jar` as a downloadable build artifact, and builds the Docker image.