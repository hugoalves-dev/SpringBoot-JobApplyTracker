package com.jobtracker.integration;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.ai.model.chat=openai",
        "spring.ai.model.embedding=none",
        "spring.ai.openai.api-key=test-only-not-a-real-key",
        "spring.ai.openai.base-url=https://openrouter.ai/api/v1",
        "spring.ai.openai.chat.model=openai/gpt-4o-mini"
})
@ActiveProfiles("test")
class OpenRouterAssistantConfigurationIT {

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private Environment environment;

    @Test
    void configuresOpenRouterThroughTheOpenAiCompatibleChatModel() {
        assertThat(chatModel.getClass().getSimpleName()).isEqualTo("OpenAiChatModel");
        assertThat(environment.getProperty("spring.ai.openai.base-url"))
                .isEqualTo("https://openrouter.ai/api/v1");
        assertThat(environment.getProperty("spring.ai.openai.chat.model"))
                .isEqualTo("openai/gpt-4o-mini");
    }
}
