# Approach

## 1. Introduction

This is a small message relay. Clients connect to the server over TCP and
register a name. They can then send messages to other registered names. The
server stores each message in the receiver's **mailbox** and delivers it as
soon as the receiver is connected. A message is only removed once the receiver
**acknowledges** it. If the receiver is offline, the message waits. If the
receiver disconnects before acknowledging, the message is delivered again
when they come back.

## 2. Requirements and where they are handled

| # | Requirement | Where it happens |
|---|---|---|
| 1 | Clients register with a unique name; many can be connected | `Broker.register` |
| 2 | Send a uniquely identified message to another client | `SEND` message, `Broker.send` |
| 3 | Tell the sender if it was accepted or rejected | `ACCEPTED` / `REJECTED` replies |
| 4 | Receiver gets the message and acknowledges it | `DELIVER` → `ACK` → `Mailbox.ack` |
| 5 | Messages for an offline receiver are kept, within limits | `Mailbox` (max 100 messages) |
| 6 | Receiver reconnects with the same name and gets them | `Broker.register` reuses the same mailbox |
| 7 | Delivered but not acknowledged → delivered again | `Mailbox.moveUnackedBackToWaiting` |

## 3. Architecture overview

```
  Alice (client)                         Bob (client)
       │  TCP                                 │  TCP
       ▼                                      ▼
  ┌──────────────────────── RelayServer ────────────────────────┐
  │  Accepts connections. Each client gets a ClientConnection   │
  │  running on its own virtual thread.                         │
  │                                                             │
  │  ClientConnection (Alice)          ClientConnection (Bob)   │
  │   reads JSON lines ──┐               ▲  writes JSON lines   │
  │                      ▼               │                      │
  │  ┌─────────────────── Broker ───────────────────────┐       │
  │  │  mailboxes:   name → Mailbox     (kept forever)  │       │
  │  │  onlineUsers: name → connection  (while online)  │       │
  │  └──────────────────────────────────────────────────┘       │
  │                      │                                      │
  │                      ▼                                      │
  │   Mailbox (one per person):  waiting → unacked → removed    │
  └─────────────────────────────────────────────────────────────┘
```

The code is split into three packages, each with one job:

| Package | Job | Knows about networking? |
|---|---|---|
| `protocol` | What the messages look like | No |
| `core` | The rules: who has which mailbox, who is online, delivery and acks | No |
| `server` | TCP connections, threads, reading and writing JSON lines | Yes |

Keeping networking out of `core` means the important rules can be tested
quickly, with no sockets involved.

### The main classes

- **`RelayServer`**: opens the port, accepts new clients, and refuses them
  when there are too many. Stops everything cleanly on shutdown.
- **`ClientConnection`**: one per connected client. Reads its messages, asks
  the Broker what to do, and sends back replies.
- **`FrameCodec`**: turns a line of JSON into a message object, and back.
- **`Broker`**: keeps every mailbox and knows who is online right now.
- **`Mailbox`**: one person's messages and where each one is in its journey.
- **`Connection`** (interface): how the Broker talks to a connected client
  ("deliver this", "close") without knowing anything about sockets. The server
  provides the real one; the tests use a simple fake one.

## 4. The protocol

### Wire format

Each message is **one line of JSON**, ending with a newline. The `type` field
says what kind of message it is.

```
{"type":"SEND","messageId":"m-1","to":"bob","body":"hi"}
```

**Why a newline?** TCP sends a continuous stream of bytes with no gaps, so the
receiver needs a way to tell where one message ends. A newline marks the end.
This is safe because JSON never contains a real newline: a newline inside a
text value is written as the two characters `\n`.

### Messages a client sends

| Type | Fields | Meaning |
|---|---|---|
| `REGISTER` | `name` | "This is who I am." Must be sent first. |
| `SEND` | `messageId`, `to`, `body` | Send a message to someone. The sender chooses a unique `messageId`. |
| `ACK` | `messageId` | "I received this message, you can delete it." |

The sender's name is **not** part of `SEND`. The server already knows who
registered on this connection, so a client can't pretend to be someone else.

### Messages the server sends

| Type | Fields | When |
|---|---|---|
| `REGISTERED` | `name` | Registration worked |
| `ACCEPTED` | `messageId` | The message is safely stored |
| `REJECTED` | `messageId`, `code` | The message was not stored (see error codes) |
| `DELIVER` | `messageId`, `from`, `body` | A message for you |
| `ACK_RESULT` | `messageId`, `status` | `ACKED` (removed) or `UNKNOWN` (nothing to remove) |
| `ERROR` | `code`, `detail` | Something about the request was wrong |

### Error codes

| Code | Meaning |
|---|---|
| `INVALID_REQUEST` | Not valid JSON, unknown type, or a missing field |
| `NOT_REGISTERED` | Sent `SEND` or `ACK` before `REGISTER` |
| `UNKNOWN_RECIPIENT` | Nobody with that name has ever registered |
| `MAILBOX_FULL` | The receiver already has 100 messages stored |
| `MESSAGE_TOO_LARGE` | The message text is over the limit |
| `SERVER_BUSY` | Too many clients connected |

## 5. Connection lifecycle

**Connecting.** The client opens a TCP connection. If 100 clients are already
connected, the server sends `ERROR SERVER_BUSY` and closes it.

**Registering.** The client sends `REGISTER` with its name. The server replies
`REGISTERED`, then immediately delivers any messages that were waiting for that
name. `REGISTERED` is always sent first, so the client knows it is registered
before messages arrive.

**Reconnecting.** A client identifies itself again simply by registering with
the same name. Mailboxes are kept even when nobody is connected, so it gets
the same mailbox back with all its waiting messages. If the same name is
already connected somewhere else, the **new connection takes over** and the old
one is closed.

**Acknowledging.** After receiving `DELIVER`, the client sends `ACK` with the
`messageId`. Only messages in that client's own mailbox can be acknowledged,
so nobody can delete someone else's messages.

**When a connection closes** (the client quits, the network drops, or the
client is silent for 5 minutes): any messages that were delivered but not yet
acknowledged go back to the **front** of that person's mailbox, in their
original order. They are delivered again the next time that name registers.
This always happens, because it runs in a `finally` block.

**Server shutdown.** Ctrl+C (or a container stop signal) runs a shutdown hook.
The server stops accepting new clients, closes every connection, and waits
briefly for its threads to finish.

## 6. How a message travels

```
Alice                      Server                           Bob
  │ SEND m-1 to bob "hi"     │                                │
  │ ───────────────────────► │ is the text small enough?      │
  │                          │ has bob ever registered?       │
  │                          │ is bob's mailbox full?         │
  │                          │ store m-1 (waiting)            │
  │ ◄──────── ACCEPTED m-1 ──│                                │
  │                          │ bob is online:                 │
  │                          │ m-1 becomes unacked            │
  │                          │──────── DELIVER m-1 ─────────► │
  │                          │ ◄───────────── ACK m-1 ─────── │
  │                          │ remove m-1                     │
  │                          │──────── ACK_RESULT ACKED ────► │
```

Inside the mailbox, each message moves through three steps:

1. **Waiting**: stored, not sent yet (for example, the receiver is offline)
2. **Unacked**: sent to the receiver, waiting for their `ACK`
   (sometimes called "in flight")
3. **Removed**: the receiver acknowledged it

## 7. Delivery rules

- **At-least-once delivery.** The server retains messages until they are
  acknowledged, and unacknowledged messages are returned to the waiting queue
  when a connection closes. Messages may be delivered more than once, for
  example if the receiver disconnects before acknowledging them. Receivers can
  spot repeats by `messageId`. Exactly-once delivery is much harder and wasn't
  needed.
- **Order.** Messages to one person are delivered in the order the server
  accepted them (first in, first out). This stays true after a reconnect,
  because unacknowledged messages go back to the front in their original order.
- **Duplicate sends.** If a client sends the same `messageId` again (for
  example, it retried after a network hiccup), the server replies `ACCEPTED`
  but doesn't store it twice. The duplicate check runs **before** the "mailbox
  full" check, so a retry is still accepted even if the mailbox has filled up
  since.
- **Repeated or stale acks.** Acknowledging something unknown or already
  acknowledged returns `ACK_RESULT UNKNOWN`. It's harmless and the connection
  stays open.
- **Unknown receiver.** Sending to a name that has never registered is
  rejected, so sending can't create mailboxes. Only registering creates one.

## 8. Limits

The server limits individual messages, mailboxes, connections and outgoing
queues. The total number of registered names is not limited yet (see Known
limitations).

| Resource | Limit | Response |
|---|---|---|
| Messages stored per person | 100 | `REJECTED MAILBOX_FULL` |
| Message text | 4096 characters | `REJECTED MESSAGE_TOO_LARGE` |
| One line of JSON | 8192 bytes | Connection closed (the stream can't be trusted any more) |
| Connected clients | 100 | `ERROR SERVER_BUSY`, then closed |
| Replies queued for one client | 100 | That client is disconnected; its messages stay safe in its mailbox |
| Silent client | 5 minutes | Connection closed |

The line limit is checked **while reading**, byte by byte, so a client can't
send an endless line and use up memory.

## 9. Concurrency: many clients at once

- **One virtual thread per client.** Each client is read on its own thread,
  so a silent or slow client never stops others being read. Virtual threads
  (Java 21) are cheap, so the simple "one thread per connection" style scales
  well.
- **One lock per mailbox.** Every `Mailbox` method is `synchronized`, so two
  people sending to Bob at the same time can't corrupt his mailbox. "Check if
  full, then add" happens under one lock, so the limit can't be exceeded.
  Different people's mailboxes have different locks, so they never wait for
  each other.
- **No deadlocks.** The code never holds two mailbox locks at the same time.
- **Thread-safe maps.** The Broker's maps are `ConcurrentHashMap`.
  `computeIfAbsent` creates a mailbox exactly once, even if two registrations
  for a new name arrive together.
- **A slow client only slows itself.** When Alice sends to Bob, the message is
  put in **Bob's outbox queue** and Alice carries on at once. A separate writer
  thread for Bob takes messages from the queue and writes them. If Bob stops
  reading and his queue fills up, only Bob is disconnected. This also means
  only one thread ever writes to Bob's socket, so messages can't get mixed up.
- **Reconnect safety.** When a connection closes, the Broker only marks the
  person offline if that connection is still their current one. An old
  connection closing late can't log out a newer one.

## 10. Errors and logging

- **Bad JSON** gets an `ERROR INVALID_REQUEST` reply, and the connection stays
  open, since the whole line was read and the stream is still in step.
- **A line that's too long** closes the connection, since we no longer know
  where the next message starts.
- **Expected situations** (mailbox full, unknown recipient) are **returned as
  results**, not thrown as exceptions, because they're normal outcomes.
- **Unexpected errors** (a bug) are caught at the connection level, logged with
  the stack trace, and only that one connection is closed. The server keeps
  running.
- **Logging** uses Java's built-in `System.Logger`. It logs connections,
  registrations, disconnects and errors, but **never message text**, which
  could be private.

## 11. Design decisions and trade-offs

**Plain TCP.** TCP provides a straightforward way to maintain persistent,
bidirectional connections and push messages to clients. HTTP polling would
require clients to repeatedly request new messages, while WebSockets or gRPC
streaming would also be viable alternatives (I've used gRPC before). I chose
plain TCP because the brief preferred low-level networking and I wanted to
implement the framing and delivery logic directly, with no generated code.

**Newline-delimited JSON instead of a length prefix.** Readable, easy to debug,
and testable by hand with `nc`. The cost is that the size isn't known up
front, so I count bytes while reading. A length prefix (send the size first)
would make the size check simpler and support binary data, but can't be typed
by hand.

**Outbox queue per client instead of writing straight to the socket.**
Writing directly is simpler, but a client that stops reading would make the
*sender's* thread wait. The queue keeps clients independent. The cost is one
extra thread and a small buffer per client.

**Simple lists in the Mailbox.** Two `ArrayList`s, `waiting` and `unacked`,
keep the code easy to read. Finding a message by ID means looping through the
list, which is fine for 100 messages; a map keyed by ID would be faster for
much larger mailboxes.

**`synchronized` instead of `ReentrantLock`.** I only need "one thread at a
time", and `synchronized` is the simplest way to say that. `ReentrantLock`
adds timeouts and fairness, which I didn't need.

**A hand-written fake instead of Mockito.** The Broker only depends on the
small `Connection` interface, so a fake class that records what it was sent
is simpler than a mocking library.

**Only two libraries.** Jackson for JSON (writing a JSON parser isn't the
interesting part) and JUnit for tests. Networking and threading use the Java
standard library.

## 12. Testing

Run with `mvn test`. There are 18 tests, all fast and deterministic.

| Test class | What it checks |
|---|---|
| `MailboxTest` | Acked messages are removed; offline messages are kept in order; unacked messages come back first after a disconnect; a full mailbox rejects; duplicates aren't stored twice |
| `BrokerTest` | Unknown receiver is rejected; an online receiver gets messages immediately; an offline receiver gets them after registering again; unacked messages are re-sent after reconnecting; only the receiver can acknowledge |
| `FrameCodecTest` | A message survives writing and reading; it's one JSON line with a `type`; a line that's too long is rejected |
| `ClientMessageTest`, `ServerMessageTest` | The message records hold their data; every server message type is handled |
| `RelayServerTest` | **End to end over real TCP:** Alice sends and Bob receives and acknowledges while online; a message sent while Bob is offline is delivered when he reconnects |

The network tests use port 0 (the operating system picks a free port) and a
5-second timeout, so they never clash with anything and never hang.

**Not covered, and why:**

- Slow-client disconnection and idle timeouts: they depend on timing, which
  makes tests slow or flaky. I'd add them with shorter limits and a client
  that deliberately stops reading.
- Many clients at once (load testing) and long-running concurrency stress:
  out of scope for the time box.
- Server restarts: there's no persistence yet, so there's nothing to test.

I focused the tests on the rules (`core`), where most of the logic is, plus
end-to-end tests to prove the pieces work together over the network.

## 13. Build, CI and artifact

- `mvn clean package` runs all the tests, then builds one runnable jar,
  `target/relay.jar`, containing the app and Jackson.
- The `Dockerfile` has two stages: a Maven image builds and tests the jar,
  then only the jar is copied into a small Java 21 runtime image. Anyone can
  run it without installing Java.
- On every push, GitHub Actions builds and tests the project on a clean Linux
  machine with Java 21, saves `relay.jar` as a build artifact, and builds the
  Docker image.

## 14. Known limitations

- **Memory only.** Messages are lost if the server restarts or crashes.
- **No limit on the number of registered names.** Each new name creates a
  mailbox that is kept forever, so a client could register many different
  names and grow memory without bound.
- **Duplicate detection lasts only while a message is stored.** If a client
  retries after the receiver has already acknowledged it, it's stored and
  delivered again. That's still within at-least-once delivery.
- **Message length is counted in characters, not bytes.**
- **All waiting messages are sent at once** on reconnect. There's no limit on
  how many can be unacked at the same time.
- **A short timing window on takeover.** If a name reconnects at the exact
  moment a message is being delivered to the old connection, that message may
  go to the closing connection. It isn't lost: it stays unacked and is
  redelivered on the next reconnect.
- **No authentication.** Anyone who knows a name can register as it.

## 15. What I would do next

1. **Mailbox lifecycle and limits:** a maximum number of mailboxes (rejecting
   new names once reached), and removing empty mailboxes that haven't been
   used for a while.
2. **Persistence and recovery:** save waiting and unacked messages to a
   database or disk, and reload them on startup.
3. **Better concurrency testing:** tests for slow clients, idle timeouts and
   many clients sending at once.
4. **Authentication,** for example a token checked on `REGISTER`.
5. **A limit on unacked messages** per client.
6. **Deployment:** push the Docker image from CI to a registry, tagged with the
   commit ID, and deploy to a test environment before production.
7. **Observability:** metrics (mailbox sizes, delivery times) and structured
   logs.

## 16. How I used AI

I used Claude as a development assistant throughout the assignment, primarily
to explore design alternatives, discuss implementation approaches, draft code
and tests, and help with documentation. I also used Claude Code for project
setup issues.

I reviewed and adapted the suggestions, simplifying areas where I felt a more
straightforward implementation was sufficient. For example, I chose
synchronized methods and standard collections over more specialised
concurrency utilities.

I ran and extended the automated tests for the core functionality, and manually
tested the server over TCP using nc. I also reviewed the implementation to
ensure I understood the behaviour, trade-offs and limitations of the solution.