package com.chhaya.relay.core;

/** Outcome of a send, as reported to the sender. */
public enum SendResult {
    ACCEPTED,           // stored (or already stored: duplicates are accepted, not stored twice)
    UNKNOWN_RECIPIENT,  // that name has never registered
    MAILBOX_FULL,       // recipient's mailbox is at its limit
    MESSAGE_TOO_LARGE   // body is over the size limit
}