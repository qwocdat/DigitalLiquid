package com.digitalsmod.digitalliquid

import android.content.Context

/** Model IDs can be extended in this list and must also be allowed by the proxy worker. */
object AiModels {
    data class Model(val label: String, val id: String)
    val all = listOf(
        Model("Gemini 3.8 Flash", "google/gemini-3.8-flash"),
        Model("GPT-4o mini", "openai/gpt-4o-mini"),
        Model("DeepSeek Chat", "deepseek/deepseek-chat"),
        Model("Qwen 3 30B", "qwen/qwen3-30b-a3b"),
        Model("Llama 3.3 70B", "meta-llama/llama-3.3-70b-instruct")
    )
    val defaultId: String get() = all.first().id

    /** Migrates outdated defaults and preserves custom provider/model IDs. */
    fun selectedId(context: Context): String {
        val prefs = AppPrefs.get(context)
        val saved = prefs.getString(AppPrefs.AI_MODEL, null).orEmpty()
        val normalized = when {
            saved.isBlank() -> defaultId
            saved == "google/gemini-2.5-flash" -> defaultId
            else -> saved
        }
        if (normalized != saved) prefs.edit().putString(AppPrefs.AI_MODEL, normalized).apply()
        return normalized
    }

    fun labelFor(id: String): String = all.firstOrNull { it.id == id }?.label ?: id

    /** Public-facing label that never leaks a custom provider/model identifier into the main UI. */
    fun displayLabelFor(id: String): String = all.firstOrNull { it.id == id }?.label ?: "Mô hình tùy chỉnh"
}
