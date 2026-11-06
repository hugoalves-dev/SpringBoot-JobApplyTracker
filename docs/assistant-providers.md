# Ask ApplyWell: Gemini and OpenRouter

Ask ApplyWell uses Spring AI's `ChatModel` abstraction. SSE streaming,
read-only tools, and conversation memory are shared by both providers.

Set the following values in your `.env` or deployment environment. Restart
the backend after changing the selected provider.

## Google AI Studio (Gemini)

```dotenv
APP_ASSISTANT_ENABLED=true
APP_ASSISTANT_PROVIDER=google-genai
GEMINI_API_KEY=your-google-ai-studio-key
GEMINI_CHAT_MODEL=gemini-2.5-flash
```

## OpenRouter

```dotenv
APP_ASSISTANT_ENABLED=true
APP_ASSISTANT_PROVIDER=openai
OPENROUTER_API_KEY=your-openrouter-key
OPENROUTER_BASE_URL=https://openrouter.ai/api/v1
OPENROUTER_CHAT_MODEL=openai/gpt-4o-mini
```

**Why `openai`?** Spring AI's built-in Chat Completions adapter uses the
`openai` provider ID. The configured base URL routes requests to
**OpenRouter**, not OpenAI directly. The model ID follows OpenRouter's
`provider/model` convention. Pick a model that supports tool calling
and streaming, as the assistant needs both.

To disable the assistant, set `APP_ASSISTANT_ENABLED=false` and
`APP_ASSISTANT_PROVIDER=none`. API keys are server-side secrets: do not
commit real values or expose them to the frontend.

There is no automatic provider fallback: selecting a provider switches
the backend on restart. OpenRouter's own provider routing and retry
behavior are separate from this selection.
