package com.jobtracker.unit.integrations;

import com.jobtracker.service.integrations.gmail.MimeDraftBuilder;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class MimeDraftBuilderTest {

    private final MimeDraftBuilder builder = new MimeDraftBuilder();

    @Test
    void buildsHtmlMessageWithPdfAttachment() throws Exception {
        byte[] pdf = "%PDF-1.7 fake".getBytes();
        String raw = builder.buildRawBase64Url("me@gmail.com",
                List.of(new InternetAddress("recruiter@corp.com")),
                List.of(new InternetAddress("cc@corp.com")),
                List.of(),
                "Candidatura – Desenvolvedor Java",
                "<p>Olá, segue meu <b>currículo</b>.</p>",
                new MimeDraftBuilder.Attachment("CV Vitor - Java.pdf", "application/pdf", pdf));

        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()),
                new ByteArrayInputStream(Base64.getUrlDecoder().decode(raw)));

        assertThat(message.getSubject()).isEqualTo("Candidatura – Desenvolvedor Java");
        assertThat(message.getFrom()[0].toString()).isEqualTo("me@gmail.com");
        assertThat(message.getRecipients(Message.RecipientType.TO)[0].toString()).isEqualTo("recruiter@corp.com");
        assertThat(message.getRecipients(Message.RecipientType.CC)[0].toString()).isEqualTo("cc@corp.com");

        Multipart mixed = (Multipart) message.getContent();
        assertThat(mixed.getCount()).isEqualTo(2);
        Multipart alternative = (Multipart) mixed.getBodyPart(0).getContent();
        assertThat(alternative.getBodyPart(0).getContent().toString()).contains("segue meu currículo");
        assertThat(alternative.getBodyPart(1).getContentType()).startsWith("text/html");
        Part attachment = mixed.getBodyPart(1);
        assertThat(attachment.getDisposition()).isEqualTo(Part.ATTACHMENT);
        assertThat(MimeUtility.decodeText(attachment.getFileName())).isEqualTo("CV Vitor - Java.pdf");
        assertThat(attachment.getInputStream().readAllBytes()).isEqualTo(pdf);
    }

    @Test
    void buildsAlternativeOnlyWithoutAttachment() throws Exception {
        String raw = builder.buildRawBase64Url("me@gmail.com", List.of(new InternetAddress("a@b.com")),
                List.of(), List.of(), "Hi", "<p>Hi</p>", null);

        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()),
                new ByteArrayInputStream(Base64.getUrlDecoder().decode(raw)));

        assertThat(message.getContentType()).startsWith("multipart/alternative");
    }
}
