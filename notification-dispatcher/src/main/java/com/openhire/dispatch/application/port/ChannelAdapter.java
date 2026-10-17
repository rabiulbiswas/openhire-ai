package com.openhire.dispatch.application.port;

import com.openhire.dispatch.domain.Delivery;

/**
 * Template method for every channel: shared address validation and failure classification around a
 * channel-specific send step. Concrete adapters are the strategies chosen per message.
 */
public abstract class ChannelAdapter {
    /** Retrying later may succeed (provider timeout, connection refused). */
    public static final class TransientFailure extends RuntimeException {
        public TransientFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Retrying cannot succeed (no address, rejected recipient). */
    public static final class PermanentFailure extends RuntimeException {
        public PermanentFailure(String message) {
            super(message);
        }
    }

    public abstract String channel();

    public abstract boolean configured();

    public final void deliver(Delivery delivery) {
        var address = address(delivery.recipient());
        if (address == null || address.isBlank())
            throw new PermanentFailure(
                    "NO_ADDRESS: the recipient has no " + channel().toLowerCase() + " contact");
        try {
            send(delivery, address);
        } catch (PermanentFailure | TransientFailure classified) {
            throw classified;
        } catch (RuntimeException unexpected) {
            throw new TransientFailure(
                    channel() + " provider error: " + unexpected.getClass().getSimpleName(), unexpected);
        }
    }

    protected abstract String address(Delivery.Recipient recipient);

    protected abstract void send(Delivery delivery, String address);
}
