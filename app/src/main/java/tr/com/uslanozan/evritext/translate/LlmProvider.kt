package tr.com.uslanozan.evritext.translate

/** LLM services the user can connect with their own API key. */
enum class LlmProvider(
    val displayName: String,
    val defaultModel: String,
) {
    GEMINI("Gemini", GeminiTranslationProvider.DEFAULT_MODEL),
    OPENAI("OpenAI", "gpt-5-mini"),
    OPENROUTER("OpenRouter", "openrouter/auto"),
    ANTHROPIC("Anthropic", "claude-haiku-4-5-20251001"),
    GROQ("Groq", "llama-3.3-70b-versatile"),
}

/** Creates the concrete adapter without leaking provider details into the service. */
fun LlmProvider.translationProvider(apiKey: String): TranslationProvider = when (this) {
    LlmProvider.GEMINI -> GeminiTranslationProvider(apiKey)
    LlmProvider.OPENAI -> OpenAiCompatibleTranslationProvider.openAi(apiKey, defaultModel)
    LlmProvider.OPENROUTER -> OpenAiCompatibleTranslationProvider.openRouter(apiKey, defaultModel)
    LlmProvider.ANTHROPIC -> AnthropicTranslationProvider(apiKey, defaultModel)
    LlmProvider.GROQ -> OpenAiCompatibleTranslationProvider.groq(apiKey, defaultModel)
}
