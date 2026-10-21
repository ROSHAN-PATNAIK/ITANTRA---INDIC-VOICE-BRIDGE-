package com.example.indicvoicebridge.model

import java.util.Locale
import java.util.UUID

data class Language(
    val displayName: String,
    val locale: Locale,
    val mlKitCode: String
) {
    fun getName(): String = displayName
    fun getLocale(): Locale = locale
}

val SUPPORTED_LANGUAGES = listOf(
    Language("English",   Locale("en", "IN"), "en"),
    Language("Hindi",     Locale("hi", "IN"), "hi"),
    Language("Telugu",    Locale("te", "IN"), "te"),
    Language("Tamil",     Locale("ta", "IN"), "ta"),
    Language("Kannada",   Locale("kn", "IN"), "kn"),
    Language("Malayalam", Locale("ml", "IN"), "ml"),
    Language("Marathi",   Locale("mr", "IN"), "mr"),
    Language("Bengali",   Locale("bn", "IN"), "bn"),
    Language("Gujarati",  Locale("gu", "IN"), "gu"),
    Language("Punjabi",   Locale("pa", "IN"), "pa")
)

enum class MessageType { MESSAGE, ALERT }

data class BridgeMessage(
    val id: String = UUID.randomUUID().toString(),
    val senderIp: String,
    val originalText: String,
    val translatedText: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val timestamp: Long = System.currentTimeMillis(),
    val type: MessageType = MessageType.MESSAGE,
    val isOutgoing: Boolean
)

data class DiscoveredPeer(
    val deviceName: String,
    val ip: String,
    val port: Int = 5050,
    val lastSeen: Long = System.currentTimeMillis()
)

