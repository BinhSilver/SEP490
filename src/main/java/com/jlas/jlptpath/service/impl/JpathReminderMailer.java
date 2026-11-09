package com.jlas.jlptpath.service.impl;

import com.jlas.auth.entity.User;
import com.jlas.auth.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * [JLAS-W0108] Email channel of the study reminder. Only called when the learner turned email on and
 * {@code learning.jlpt-path.reminder.email-enabled} is true; without a mail sender or an address nothing is sent.
 */
@Slf4j
@Component
public class JpathReminderMailer {

    static final String SUBJECT_PREFIX = "KotsuKotsu · ";
    static final String FOOTER =
            "\n\nMở KotsuKotsu và vào Lộ trình học để bắt đầu.\nTắt nhắc qua email trong Cài đặt lộ trình.";

    private final ObjectProvider<JavaMailSender> senders;
    private final UserRepository users;
    private final String from;

    public JpathReminderMailer(ObjectProvider<JavaMailSender> senders, UserRepository users,
            @Value("${app.mail.from:${spring.mail.username:no-reply@localhost}}") String from) {
        this.senders = senders;
        this.users = users;
        this.from = from;
    }

    /** Sends the reminder to the learner's address; false when there is no sender or address, or sending failed. */
    public boolean send(Long userId, String title, String message) {
        JavaMailSender sender = senders.getIfAvailable();
        if (sender == null) {
            return false;
        }
        String to = users.findById(userId).map(User::getEmail).orElse(null);
        if (to == null || to.isBlank()) {
            return false;
        }
        try {
            SimpleMailMessage mail = new SimpleMailMessage();
            mail.setFrom(from);
            mail.setTo(to);
            mail.setSubject(SUBJECT_PREFIX + title);
            mail.setText(message + FOOTER);
            sender.send(mail);
            return true;
        } catch (RuntimeException e) {
            log.warn("Study reminder email failed for user {}: {}", userId, e.getMessage());
            return false;
        }
    }
}
