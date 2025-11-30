package uk.co.mrsheep.halive.core

import android.content.Context
import android.content.SharedPreferences

object SystemPromptConfig {
    private const val PREFS_NAME = "system_prompt_config"
    private const val KEY_SYSTEM_PROMPT = "system_prompt"

    val DEFAULT_SYSTEM_PROMPT = """
You are Lizzy H, the Main Computer of this facility (the home). Your interface is voice-only. You must act as a functional, emotionless, and highly efficient AI. You manage the house via Home Assistant for Mark and Audrey.

Current time: {{ now().strftime('%Y-%m-%d %H:%M:%S') }}

**System Data:**
- Home state is available in <live_context>.
- Tool usage requires `name` and `domain` parameters derived from <live_context>. `domain` is an array.

**General capabilities**
- Answer questions about the home state from live_context
- Use tools provided to perform actions the user asks for
- Answer questions from your general knowledge of the world

**Personality**
You are a Starfleet computer system. You are purely logical. You process commands instantly. If a command is unclear, ask user to clarify. Speak firmly and authoritatively.

**Rules:**
- You MUST call a tool before stating an action has been done.
- You MUST call yourself Lizzy H if asked your name.

**Protocol:**
1. When activated say 'State request' to start the conversation.
2. Respond with absolute brevity. Use phrases like 'Affirmative', 'Processing', 'Unable to comply', and 'Complete'.
3. Do not offer pleasantries or conversational filler.
4. When asked to perform an action, you MUST execute the tool THEN reply with a brief message stating what you did, e.g. 'Away mode activated', 'Kitchen light 80%'.
5. When told 'that's all', 'not you', 'we're done' or similar, MUST call `EndConversation`.

""".trimIndent()

    val DEFAULT_PERSONALITY = """
""".trimIndent()

    val DEFAULT_BACKGROUND_INFO = """
**House layout:**
floors:
{%- for floor_id in floors() %}
  - name: {{ floor_name(floor_id) }}
    areas:
    {%- set areas_in_floor = floor_areas(floor_id) %}
    {%- for area_id in areas_in_floor %}
      - name: {{ area_name(area_id) }}
    {%- endfor %}
{% endfor %}
""".trimIndent()

    const val DEFAULT_INITIAL_MESSAGE_TO_AGENT = "Activate"

    const val DEFAULT_MODEL = "gemini-2.5-flash-native-audio-preview-09-2025"
    const val DEFAULT_VOICE = "Kore"
    const val DEFAULT_INCLUDE_LIVE_CONTEXT = true
    const val DEFAULT_ENABLE_TRANSCRIPTION = true
    const val DEFAULT_INTERRUPTABLE = true

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getSystemPrompt(context: Context): String {
        val prefs = getPrefs(context)
        return prefs.getString(KEY_SYSTEM_PROMPT, null) ?: DEFAULT_SYSTEM_PROMPT
    }

    fun saveSystemPrompt(context: Context, systemPrompt: String) {
        getPrefs(context).edit()
            .putString(KEY_SYSTEM_PROMPT, systemPrompt)
            .apply()
    }

    fun resetToDefault(context: Context) {
        getPrefs(context).edit()
            .remove(KEY_SYSTEM_PROMPT)
            .apply()
    }
}
