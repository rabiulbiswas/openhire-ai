package com.openhire.platform.event;

public interface OutboxPort {
    void append(OutboxMessage message);
}
