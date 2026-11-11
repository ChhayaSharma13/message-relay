package com.chhaya.relay.protocol;

public enum AckStatus {
    ACKED,    // message was awaiting ack for this client and is now removed
    UNKNOWN   // never existed, already acked
}