package tr.com.uslanozan.evritext.translate

/** Extra signal that measurably improves quality on ASR input. */
data class TranslationContext(
    val videoTitle: String = "",
    val before: List<String> = emptyList(),
    val after: List<String> = emptyList(),
)

/**
 * Mirrors `interface TranslationProvider` in DESIGN.md section 5 and the Python
 * Kept provider-neutral so swapping Gemini for another service is one class.
 */
interface TranslationProvider {
    val id: String
    val displayName: String

    /** Returns one translation per input sentence, same order, 1:1. */
    suspend fun translate(
        sentences: List<String>,
        sourceLang: String?,
        targetLang: String,
        context: TranslationContext,
    ): List<String>
}

/**
 * Bumped whenever the prompt changes, because it is part of the cache key: an old
 * translation made with a different prompt must not be served as a hit.
 */
const val PROMPT_VERSION = "v4"

/**
 * The model marks a speaker change with this instead of a newline: a raw newline
 * inside a JSON string makes the whole reply unparseable, which cost Phase 0 two
 * chunks of a film before it was spotted.
 */
const val TURN_MARKER = " || "

val SYSTEM_INSTRUCTION = """
You translate video subtitles from {source} into {target}.

The input is automatic speech recognition (ASR) output. It usually has misheard
words, wrong or entirely missing punctuation, and no capitalisation. Use the
surrounding context to work out what was actually said, silently fix obvious
recognition errors, then translate.

Rules:
- Translate naturally and idiomatically. Never translate word by word.
- Preserve register and tone: casual speech stays casual, slang stays slang.
- Keep proper nouns, brand names and established technical terms in their
  original form whenever that is what a native {target} speaker would actually
  say.
- Keep each translation short enough to read comfortably as a TV subtitle:
  aim for at most two lines' worth of text, roughly 80 characters. Tighten the
  wording rather than dropping meaning.
- ASR gives no speaker labels. When one item clearly contains more than one
  speaker's turn, mark it the way subtitles do: start each turn with "- " and
  separate the turns with the exact marker " || ". Example value:
  "- Merhaba. || - Merhaba, nasılsın?"
  Use " || " and never a real line break — a raw newline inside a JSON string
  breaks the reply. Only do this on a real speaker change. Never add a dash to a
  single speaker's line, and never invent character names.
- Return EXACTLY one translation per input item, in the same order, preserving
  each item's "i" index. Never merge, split, add or drop items.
- Reply with nothing but a JSON array of {"i": <int>, "tr": "<translation>"}.
""".trimIndent()
