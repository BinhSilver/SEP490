package com.jlas.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Async;

@Service
@Slf4j
@RequiredArgsConstructor
public class EmailService {
    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    @Value("${app.mail.from:${spring.mail.username:no-reply@localhost}}")
    private String from;

    @Value("${jlas.auth.verification-token-ttl:900}")
    private long verificationTokenTtl;

    @Async
    public void sendVerificationEmail(String toEmail, String fullName, String otp) {
        String name = fullName == null || fullName.isBlank() ? "Learner" : fullName;
        String subject = "[KotsuKotsu] Ma OTP xac thuc tai khoan";
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

    @Async
    public void sendPasswordResetOtp(String toEmail, String fullName, String otp) {
        String name = fullName == null || fullName.isBlank() ? "Learner" : fullName;
        send(toEmail, "[KotsuKotsu] Ma OTP dat lai mat khau",
                "Xin chao %s,%n%nMa OTP dat lai mat khau cua ban la: %s%nMa co hieu luc trong 15 phut.%n"
                        .formatted(name, otp), otp);
    }

    @Async
    public void sendEmailChangeOtp(String toEmail, String fullName, String otp, boolean newAddress) {
        String name = fullName == null || fullName.isBlank() ? "bạn" : fullName;
        String address = newAddress ? "email mới" : "email hiện tại";
        send(toEmail, "[KotsuKotsu] Xác nhận đổi email",
                "Xin chào %s,%n%nMã OTP xác nhận gửi tới %s là: %s%n"
                        .formatted(name, address, otp)
                        + "Mã có hiệu lực trong 15 phút. Không chia sẻ mã này với bất kỳ ai.\n",
                otp);
    }

    @Async
    public void sendEmailChangedNotice(String toEmail, String fullName, String newEmail) {
        String name = fullName == null || fullName.isBlank() ? "bạn" : fullName;
        send(toEmail, "[KotsuKotsu] Email tài khoản đã được thay đổi",
                "Xin chào %s,%n%nEmail đăng nhập KotsuKotsu đã được đổi thành %s.%n"
                        .formatted(name, newEmail)
                        + "Nếu bạn không thực hiện thay đổi này, hãy liên hệ hỗ trợ ngay.\n",
                newEmail);
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
