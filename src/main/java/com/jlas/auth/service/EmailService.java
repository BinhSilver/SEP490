package com.jlas.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class EmailService {
    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    @Value("${app.mail.from:${spring.mail.username:no-reply@localhost}}")
    private String from;

    @Value("${jlas.auth.verification-token-ttl:900}")
    private long verificationTokenTtl;

    public void sendVerificationEmail(String toEmail, String fullName, String otp) {
        String name = fullName == null || fullName.isBlank() ? "Learner" : fullName;
        String subject = "[JLAS] Ma OTP xac thuc tai khoan";
        String body = "Xin chao %s,%n%nMa OTP xac thuc cua ban la: %s%nMa co hieu luc trong %d phut.%n"
                .formatted(name, otp, Math.max(1, verificationTokenTtl / 60));
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            log.warn("DEV OTP for {}: {} (mail is not configured)", toEmail, otp);
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(toEmail);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
            log.info("Verification email sent to {}", toEmail);
        } catch (RuntimeException exception) {
            log.error("Could not send verification email to {}: {}", toEmail, exception.getMessage());
            log.warn("DEV OTP for {}: {}", toEmail, otp);
        }
    }

    public void sendPasswordResetEmail(String toEmail, String fullName, String token) {
        String name = fullName == null || fullName.isBlank() ? "Learner" : fullName;
        String resetUrl = "http://localhost:8080/reset-password?token=" + token;
        send(toEmail, "[JLAS] Dat lai mat khau",
                "Xin chao %s,%n%nMo lien ket sau de dat lai mat khau:%n%s%n".formatted(name, resetUrl), token);
    }

    private void send(String toEmail, String subject, String body, String developmentValue) {
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            log.warn("DEV mail value for {}: {} (mail is not configured)", toEmail, developmentValue);
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(toEmail);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
            log.info("Email sent to {}", toEmail);
        } catch (RuntimeException exception) {
            log.error("Could not send email to {}: {}", toEmail, exception.getMessage());
            log.warn("DEV mail value for {}: {}", toEmail, developmentValue);
        }
    }
}
