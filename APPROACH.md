# Approach

## What this is

A small message relay in Java 21. 
Clients will register a name, send messages
to each other through the server, and confirm (acknowledge) each message they
receive. 
The server will keep messages until they are confirmed.

## How the code is organised

The code is split into packages by responsibility:

- protocol: the messages that goes between client and server
- core: the rules and stored data

## The messages (protocol)
#### Messages a client sends:

- Register(name): "this is who I am"
- Send(messageId, to, body): send a message to someone
- Ack(messageId): "I received this message"

#### Messages the server sends:

- Registered, Accepted, Rejected, Deliver, AckResult, ErrorReply

ClientMessage and ServerMessage are sealed interfaces with records inside.
Sealed means only these message types are allowed, so the compiler can check
that every type is handled.

Errors use the ErrorCode enum, for example MAILBOX_FULL or UNKNOWN_RECIPIENT.

## The mailbox (core)

Each person has one Mailbox. A message moves through three stages:

1. **Queued**: stored, waiting to be delivered
2. **Awaiting ack** (also called "in-flight"): delivered, waiting for an acknowledgement
3. **Removed**: acknowledged

If the person disconnects before acknowledging, messages awaiting ack go back to
the front of the queue, so they are delivered again first, in the same order.

The mailbox also:

- **Has a size limit.** When full, new messages are rejected (FULL).
- **Ignores duplicates.** The same message ID is accepted but only stored once.
- **Is thread-safe.** Each mailbox has its own lock, so two people sending to
  the same person at the same time can't break it.

## Tests

Run all tests with:

    mvn test

Current tests check that:

- an acknowledged message is removed
- messages for an offline person are kept in order
- an unacknowledged message is delivered again after a disconnect

## Still to do

- Broker (registration and routing between mailboxes)
- TCP server
- Full documentation of the protocol and connection lifecycle