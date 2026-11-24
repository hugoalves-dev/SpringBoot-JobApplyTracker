package com.jobtracker.service.integrations.gmail;

import jakarta.activation.DataHandler;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Base64;
import java.util.List;
import java.util.Properties;

/** Builds the RFC 2822 message for {@code users.drafts.create}: HTML + plain-text alternative, optional PDF. */
@Component
public class MimeDraftBuilder {

    public record Attachment(String fileName, String contentType, byte[] content) {}

    public String buildRawBase64Url(String from, List<InternetAddress> to, List<InternetAddress> cc,
                                    List<InternetAddress> bcc, String subject, String html, Attachment attachment) {
        try {
            MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
            message.setFrom(new InternetAddress(from));
            message.setRecipients(MimeMessage.RecipientType.TO, to.toArray(InternetAddress[]::new));
            if (!cc.isEmpty()) {
                message.setRecipients(MimeMessage.RecipientType.CC, cc.toArray(InternetAddress[]::new));
            }
            if (!bcc.isEmpty()) {
                message.setRecipients(MimeMessage.RecipientType.BCC, bcc.toArray(InternetAddress[]::new));
            }
            message.setSubject(subject, "UTF-8");

            MimeMultipart alternative = new MimeMultipart("alternative");
            MimeBodyPart text = new MimeBodyPart();
            text.setText(Jsoup.parse(html).wholeText().strip(), "UTF-8");
            MimeBodyPart htmlPart = new MimeBodyPart();
            htmlPart.setContent(html, "text/html; charset=UTF-8");
            alternative.addBodyPart(text);
            alternative.addBodyPart(htmlPart);

            if (attachment == null) {
                message.setContent(alternative);
            } else {
                MimeMultipart mixed = new MimeMultipart("mixed");
                MimeBodyPart body = new MimeBodyPart();
                body.setContent(alternative);
                mixed.addBodyPart(body);
                MimeBodyPart file = new MimeBodyPart();
                file.setDataHandler(new DataHandler(new ByteArrayDataSource(attachment.content(), attachment.contentType())));
                file.setFileName(jakarta.mail.internet.MimeUtility.encodeText(attachment.fileName(), "UTF-8", null));
                file.setDisposition(MimeBodyPart.ATTACHMENT);
                mixed.addBodyPart(file);
                message.setContent(mixed);
            }
            message.saveChanges();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            message.writeTo(out);
            return Base64.getUrlEncoder().encodeToString(out.toByteArray());
        } catch (UnsupportedEncodingException ex) {
            throw new IllegalStateException("UTF-8 unsupported", ex);
        } catch (MessagingException | IOException ex) {
            throw new IllegalStateException("Could not build the email message", ex);
        }
    }
}
