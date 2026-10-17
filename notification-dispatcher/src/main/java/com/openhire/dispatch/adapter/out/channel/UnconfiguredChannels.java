package com.openhire.dispatch.adapter.out.channel;

import com.openhire.dispatch.application.port.ChannelAdapter;
import com.openhire.dispatch.domain.Delivery;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SMS and WhatsApp stay disabled until a provider contract and credentials are configured. The dispatcher
 * then falls back to email when the message allows it, and reports CHANNEL_NOT_CONFIGURED otherwise.
 */
@Configuration
class UnconfiguredChannels {
    @Bean
    ChannelAdapter smsChannel() {
        return new Unconfigured("SMS");
    }

    @Bean
    ChannelAdapter whatsAppChannel() {
        return new Unconfigured("WHATSAPP");
    }

    static final class Unconfigured extends ChannelAdapter {
        private final String channel;

        Unconfigured(String channel) {
            this.channel = channel;
        }

        @Override
        public String channel() {
            return channel;
        }

        @Override
        public boolean configured() {
            return false;
        }

        @Override
        protected String address(Delivery.Recipient recipient) {
            return recipient.phone();
        }

        @Override
        protected void send(Delivery delivery, String address) {
            throw new PermanentFailure("CHANNEL_NOT_CONFIGURED: " + channel);
        }
    }
}
