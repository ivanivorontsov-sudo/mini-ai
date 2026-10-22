package com.example.llama

import kotlinx.coroutines.flow.MutableStateFlow

data class ModelInfo(
    val id: String, val title: String, val url: String, val fileName: String,
    val size: Long, val sha256: String, val ramGb: Int, val note: String,
    /** send the user's GitHub token (private repo asset); the token never ships in the APK */
    val githubAuth: Boolean = false,
    /** release tag of a dynamically resolved model (Ivan GPT) */
    val version: String = "",
    /** asset is a .zip that contains the .gguf */
    val zipped: Boolean = false
)

object Models {
    val list = listOf(
        ModelInfo("qwen7b", "Мощная: Qwen2.5 7B Instruct",
            "https://huggingface.co/bartowski/Qwen2.5-7B-Instruct-GGUF/resolve/main/Qwen2.5-7B-Instruct-Q4_K_M.gguf",
            "Qwen2.5-7B-Instruct-Q4_K_M.gguf", 4683074240L,
            "65b8fcd92af6b4fefa935c625d1ac27ea29dcb6ee14589c55a8f115ceaaa1423", 8,
            "Самая умная, хороший русский. Для флагманов (8+ ГБ ОЗУ). Apache 2.0."),
        ModelInfo("qwen3_4b", "Средняя: Qwen3 4B Instruct 2507 (Честный режим)",
            "https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
            "Qwen3-4B-Instruct-2507-Q4_K_M.gguf", 2497281120L,
            "3605803b982cb64aead44f6c1b2ae36e3acdb41d8e46c8a94c6533bc4c67e597", 6,
            "Лучший баланс. По умолчанию включён «Честный» профиль. Apache 2.0."),
        ModelInfo("qwen1_5b", "Лёгкая: Qwen2.5 1.5B Instruct",
            "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            "qwen2.5-1.5b-instruct-q4_k_m.gguf", 1117320736L,
            "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e", 3,
            "Быстрая, для слабых телефонов. Apache 2.0."),
        ModelInfo("qwen05b", "Крошечная: Qwen2.5 0.5B Instruct",
            "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            "qwen2.5-0.5b-instruct-q4_k_m.gguf", 491400032L,
            "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db", 2,
            "Очень быстрая, но слабая. Для проверки. Apache 2.0."),
    )
    /** Entries resolved at runtime (Ivan GPT latest release, own model by URL). */
    val dynamic = java.util.concurrent.ConcurrentHashMap<String, ModelInfo>()
    fun byId(id: String?) = dynamic[id] ?: list.firstOrNull { it.id == id }
}

sealed class DlState {
    object Idle : DlState()
    data class Running(val model: ModelInfo, val done: Long, val total: Long) : DlState()
    data class Verifying(val model: ModelInfo) : DlState()
    data class Paused(val model: ModelInfo, val done: Long) : DlState()
    data class Done(val model: ModelInfo) : DlState()
    data class Failed(val model: ModelInfo, val error: String) : DlState()
}

object DlBus { val state = MutableStateFlow<DlState>(DlState.Idle) }
