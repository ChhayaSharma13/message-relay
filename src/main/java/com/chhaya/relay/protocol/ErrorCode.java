package com.chhaya.relay.protocol;

public enum ErrorCode {
    INVALID_REQUEST,    // malformed JSON or missing fields
    MESSAGE_TOO_LARGE,  // body or frame over the size limit
    NOT_REGISTERED,     // sent SEND/ACK before REGISTER
    UNKNOWN_RECIPIENT,  // recipient name has never registered
    MAILBOX_FULL,       // recipient's mailbox is at its limit (backpressure)
    SERVER_BUSY         // too many active connections
}
