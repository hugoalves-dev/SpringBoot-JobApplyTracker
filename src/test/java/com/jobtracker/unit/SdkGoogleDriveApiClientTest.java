package com.jobtracker.unit;

import com.google.api.services.docs.v1.Docs;
import com.google.api.services.docs.v1.model.*;
import java.util.List;

import com.jobtracker.config.GoogleDriveProperties;
import com.jobtracker.service.DriveClientFactory;
import com.jobtracker.service.SdkGoogleDriveApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SdkGoogleDriveApiClientTest {
    private SdkGoogleDriveApiClient client(boolean withAuthorizationUri) {
        var builder = ClientRegistration.withRegistrationId("google").clientId("client-id")
                .clientSecret("secret").authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenUri("https://oauth2.googleapis.com/token");
        if (withAuthorizationUri) builder.authorizationUri("https://accounts.google.com/o/oauth2/v2/auth");
        return new SdkGoogleDriveApiClient(mock(GoogleDriveProperties.class), builder.build(),
                mock(DriveClientFactory.class), mock(), mock());
    }

    @Test
    void buildAuthorizationUrl_rejectsMissingAuthorizationUriClearly() {
        assertThatThrownBy(() -> client(false).buildAuthorizationUrl("state"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("authorization URI");
    }

    @Test
    void exchangeAuthorizationCode_rejectsMissingRedirectUriClearly() {
        assertThatThrownBy(() -> client(true).exchangeAuthorizationCode("code"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("redirect URI");
    }

    @Test
    void buildAuthorizationUrl_preservesValidGoogleOAuthParameters() {
        var registration = ClientRegistration.withRegistrationId("google").clientId("client-id")
                .clientSecret("secret").authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://oauth2.googleapis.com/token").redirectUri("https://example.com/callback")
                .scope("drive", "documents.readonly").build();
        var client = new SdkGoogleDriveApiClient(mock(GoogleDriveProperties.class), registration,
                mock(DriveClientFactory.class), mock(), mock());

        String url = client.buildAuthorizationUrl("state-value");
        assertThat(url).startsWith("https://accounts.google.com/o/oauth2/v2/auth?")
                .contains("client_id=client-id", "response_type=code", "state=state-value", "access_type=offline",
                        "redirect_uri=https://example.com/callback");
    }

    @Test
    void readGoogleDocText_preservesParagraphsAndNestedTables() throws Exception {
        var factory = mock(DriveClientFactory.class);
        var docs = mock(Docs.class, RETURNS_DEEP_STUBS);
        when(factory.createDocs("token")).thenReturn(docs);
        var paragraph = new StructuralElement().setParagraph(
                new Paragraph().setElements(List.of(
                        new ParagraphElement().setTextRun(
                                new TextRun().setContent("Hello")))));
        var table = new StructuralElement().setTable(
                new Table().setTableRows(List.of(
                        new TableRow().setTableCells(List.of(
                                new TableCell().setContent(List.of(paragraph)))))));
        var document = new Document().setBody(
                new Body().setContent(List.of(paragraph, table)));
        when(docs.documents().get("doc").execute()).thenReturn(document);
        var registration = ClientRegistration.withRegistrationId("google").clientId("id")
                .clientSecret("secret").authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenUri("https://oauth2.googleapis.com/token").build();
        var client = new SdkGoogleDriveApiClient(mock(GoogleDriveProperties.class), registration, factory, mock(), mock());

        assertThat(client.readGoogleDocText("token", "doc")).isEqualTo("Hello\nHello");
        when(docs.documents().get("doc").execute()).thenReturn(new Document());
        assertThat(client.readGoogleDocText("token", "doc")).isEmpty();
    }

}
