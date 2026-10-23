package com.openhire.dispatch.client.channel;

import com.openhire.dispatch.domain.Delivery;
import com.openhire.dispatch.service.port.ChannelAdapter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** Email through SMTP (Mailpit locally; SES or a corporate relay in production). */
@Component
class SmtpEmailAdapter extends ChannelAdapter {
    private final ObjectProvider<JavaMailSender> mail;
    private final String from;
    private final String host;

    SmtpEmailAdapter(
            ObjectProvider<JavaMailSender> mail,
            @Value("${openhire.dispatch.email.from:OpenHire <no-reply@openhire.local>}") String from,
            @Value("${spring.mail.host:}") String host) {
        this.mail = mail;
        this.from = from;
        this.host = host;
    }

    @Override
    public String channel() {
        return "EMAIL";
    }

    @Override
    public boolean configured() {
        return !host.isBlank() && mail.getIfAvailable() != null;
    }

    @Override
    protected String address(Delivery.Recipient recipient) {
        return recipient.email();
    }

    @Override
    protected void send(Delivery delivery, String address) {
        var message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(address);
        message.setSubject(delivery.subject() == null ? "Message from OpenHire" : delivery.subject());
        message.setText(delivery.body());
        try {
            mail.getObject().send(message);
        } catch (MailParseException invalid) {
            throw new PermanentFailure("INVALID_ADDRESS: " + invalid.getMessage());
        } catch (MailAuthenticationException | MailSendException unavailable) {
            throw new TransientFailure(
                    "SMTP unavailable: " + unavailable.getClass().getSimpleName(), unavailable);
        }
    }
}
