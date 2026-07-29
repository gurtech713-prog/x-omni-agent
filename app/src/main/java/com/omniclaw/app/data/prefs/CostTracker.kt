package com.omniclaw.app.data.prefs

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent cost tracking across all sessions. Stores per-session token usage
 * in a JSON file so the user can see total cost over time, not just per-session.
 *
 * The cost dashboard in Settings shows:
 *   - Total tokens used (all-time)
 *   - Total estimated cost (based on per-provider rates)
 *   - Per-provider breakdown
 *   - Recent sessions list with their token counts
 */
@Singleton
class CostTracker @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {

    @Serializable
    data class SessionUsage(
        val sessionId: String,
        val provider: String,
        val model: String,
        val promptTokens: Long,
        val completionTokens: Long,
        val totalTokens: Long,
        val timestamp: Long,
        val title: String,
    )

    @Serializable
    data class ProviderRate(
        val provider: String,
        val inputPerMillion: Double,   // USD per 1M input tokens
        val outputPerMillion: Double,  // USD per 1M output tokens
    )

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val serializer = ListSerializer(SessionUsage.serializer())
    private val file: File by lazy { File(ctx.filesDir, "cost_tracker.json") }

    private val _usages = MutableStateFlow<List<SessionUsage>>(emptyList())
    val usages: Flow<List<SessionUsage>> = _usages.asStateFlow()

    // Default rates (approximate, USD per 1M tokens). Users can override in Settings.
    private val _rates = MutableStateFlow(defaultRates)
    val rates: Flow<List<ProviderRate>> = _rates.asStateFlow()

    init {
        load()
    }

    /**
     * Record a completed session's token usage. Called when a session ends
     * (DONE / FAILED / STOPPED).
     */
    suspend fun recordSession(usage: SessionUsage) = withContext(Dispatchers.IO) {
        val current = _usages.value.toMutableList()
        // Remove any existing entry with the same sessionId (dedup)
        current.removeAll { it.sessionId == usage.sessionId }
        current.add(usage)
        // Keep only the last 500 sessions to avoid unbounded growth
        if (current.size > 500) {
            current.drop(current.size - 500)
        }
        _usages.value = current
        persist(current)
    }

    /**
     * Update a provider's cost rate.
     */
    suspend fun setRate(provider: String, inputPerMillion: Double, outputPerMillion: Double) {
        val current = _rates.value.toMutableList()
        current.removeAll { it.provider == provider }
        current.add(ProviderRate(provider, inputPerMillion, outputPerMillion))
        _rates.value = current
        persistRates(current)
    }

    /**
     * Calculate the estimated cost for a session based on its provider's rates.
     */
    fun estimateCost(usage: SessionUsage): Double {
        val rate = _rates.value.firstOrNull { it.provider.equals(usage.provider, ignoreCase = true) }
            ?: return 0.0
        val inputCost = (usage.promptTokens / 1_000_000.0) * rate.inputPerMillion
        val outputCost = (usage.completionTokens / 1_000_000.0) * rate.outputPerMillion
        return inputCost + outputCost
    }

    /**
     * Total tokens across all sessions.
     */
    fun totalTokens(): Long = _usages.value.sumOf { it.totalTokens }

    /**
     * Total estimated cost across all sessions.
     */
    fun totalCost(): Double = _usages.value.sumOf { estimateCost(it) }

    /**
     * Per-provider breakdown: (provider, totalTokens, totalCost).
     */
    fun perProviderBreakdown(): List<Triple<String, Long, Double>> {
        return _usages.value
            .groupBy { it.provider }
            .map { (provider, sessions) ->
                Triple(provider, sessions.sumOf { it.totalTokens }, sessions.sumOf { estimateCost(it) })
            }
            .sortedByDescending { it.second }
    }

    /**
     * Clear all stored usage data.
     */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        _usages.value = emptyList()
        runCatching { file.delete() }
    }

    // ---- Persistence ----

    private fun load() {
        val ratesFile = File(ctx.filesDir, "provider_rates.json")
        runCatching {
            if (ratesFile.exists()) {
                val text = ratesFile.readText()
                _rates.value = json.decodeFromString(
                    ListSerializer(ProviderRate.serializer()), text
                )
            }
        }
        runCatching {
            if (file.exists()) {
                val text = file.readText()
                _usages.value = json.decodeFromString(serializer, text)
            }
        }
    }

    private fun persist(usages: List<SessionUsage>) {
        runCatching {
            val text = json.encodeToString(serializer, usages)
            val tmp = File(file.parentFile, "cost_tracker.json.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
        }
    }

    private fun persistRates(rates: List<ProviderRate>) {
        runCatching {
            val ratesFile = File(ctx.filesDir, "provider_rates.json")
            val text = json.encodeToString(ListSerializer(ProviderRate.serializer()), rates)
            ratesFile.writeText(text)
        }
    }

    companion object {
        private val defaultRates = listOf(
            ProviderRate("OPENAI_COMPAT", 0.15, 0.60),  // GPT-4o-mini approx
            ProviderRate("GEMINI", 0.075, 0.30),         // Gemini 1.5 Flash approx
            ProviderRate("LITERT", 0.0, 0.0),            // On-device, no API cost
        )
    }
}
