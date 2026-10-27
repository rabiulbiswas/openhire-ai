package com.openhire.identity.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class CandidateCodeMail {
    private final ObjectProvider<JavaMailSender> senders;
    private final String from;
    public CandidateCodeMail(ObjectProvider<JavaMailSender> senders, @Value("${openhire.candidates.mail-from:noreply@openhire.local}") String from) {
        this.senders = senders; this.from = from;
    }
    public void send(String email, String company, String code) {
        var sender = senders.getIfAvailable();
        if (sender == null) throw new IllegalStateException("Verification email delivery is not configured");
        var message = new SimpleMailMessage();
        message.setFrom(from); message.setTo(email);
        message.setSubject("Verify your candidate account · " + company);
        message.setText("Verify your email address\n\nYour " + company + " candidate registration code is:\n\n" + code
                + "\n\nThis code expires in 10 minutes and can be used only once. Do not share it.\n"
                + "If you did not request this account, ignore this email. No account is created until verification succeeds.\n\nOpenHire · Candidate accounts");
        sender.send(message);
    }
}
