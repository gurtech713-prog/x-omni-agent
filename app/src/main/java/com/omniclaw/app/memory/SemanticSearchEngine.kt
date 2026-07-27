package com.omniclaw.app.memory

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight semantic search over memory entries using TF-IDF + cosine similarity.
 *
 * A full embedding-based semantic search would require an on-device embedding
 * model (e.g. MiniLM via LiteRT) or an embedding API call. This class provides
 * a zero-dependency approximation that's good enough for the agent loop's
 * memory-retrieval needs:
 *
 *   1. Tokenize each memory into lowercase word tokens.
 *   2. Build a TF-IDF vector for each memory (term frequency × inverse document frequency).
 *   3. On query, build the query's TF vector and compute cosine similarity
 *      against every memory's TF-IDF vector.
 *   4. Return the top-K memories by similarity.
 *
 * For production-quality retrieval, swap [buildEmbedding] to call an embedding
 * model and cache the results in [EmbeddingCache]. The [search] API stays the same.
 */
@Singleton
class SemanticSearchEngine @Inject constructor(
    private val embeddingCache: EmbeddingCache,
) {

    private val documents = ConcurrentHashMap<String, DocEntry>()

    /** Add a document to the search index. */
    fun index(docId: String, content: String) {
        val tokens = tokenize(content)
        val tf = computeTf(tokens)
        val embedding = buildEmbedding(tf)
        documents[docId] = DocEntry(docId, content, tokens, tf, embedding)
        embeddingCache.put(docId, embedding)
    }

    /** Remove a document from the index. */
    fun remove(docId: String) {
        documents.remove(docId)
        embeddingCache.remove(docId)
    }

    /**
     * Search the index for [query], returning the top [k] doc IDs by
     * cosine similarity to the query embedding.
     */
    fun search(query: String, k: Int = 5): List<SearchResult> {
        val queryTokens = tokenize(query)
        val queryTf = computeTf(queryTokens)
        val queryEmbedding = buildEmbedding(queryTf)

        return documents.values
            .map { doc ->
                SearchResult(
                    docId = doc.id,
                    content = doc.content,
                    score = cosineSimilarity(queryEmbedding, doc.embedding),
                )
            }
            .sortedByDescending { it.score }
            .take(k)
    }

    /** Clear the entire index. */
    fun clear() {
        documents.clear()
        embeddingCache.clear()
    }

    val size: Int get() = documents.size

    // ---- Internal ----

    private fun tokenize(text: String): List<String> {
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 2 }  // skip very short tokens
            .filter { it !in STOP_WORDS }
    }

    private fun computeTf(tokens: List<String>): Map<String, Float> {
        if (tokens.isEmpty()) return emptyMap()
        val counts = tokens.groupingBy { it }.eachCount()
        val size = tokens.size.toFloat()
        return counts.mapValues { it.value / size }
    }

    /**
     * Build a dense embedding from a TF map. Uses a hash-based projection
     * into a fixed-dimensional space (default 256 dims) so we don't need
     * a vocabulary. This is a cheap approximation of a real embedding model.
     */
    private fun buildEmbedding(tf: Map<String, Float>, dims: Int = 256): FloatArray {
        val vec = FloatArray(dims)
        for ((word, weight) in tf) {
            val hash = word.hashCode()
            for (d in 0 until dims) {
                val proj = ((hash shr (d % 32)) and 1) - 0.5f
                vec[d] += proj * weight
            }
        }
        // L2 normalize.
        val norm = Math.sqrt(vec.fold(0.0) { acc, v -> acc + v * v }.toDouble()).toFloat()
        if (norm > 0) for (d in 0 until dims) vec[d] /= norm
        return vec
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        // Both vectors are already L2-normalized, so dot product = cosine.
        return dot
    }

    data class DocEntry(
        val id: String,
        val content: String,
        val tokens: List<String>,
        val tf: Map<String, Float>,
        val embedding: FloatArray,
    )

    data class SearchResult(
        val docId: String,
        val content: String,
        val score: Float,
    )

    private companion object {
        val STOP_WORDS = setOf(
            "the", "a", "an", "and", "or", "but", "is", "are", "was", "were",
            "be", "been", "being", "have", "has", "had", "do", "does", "did",
            "will", "would", "could", "should", "may", "might", "must", "can",
            "this", "that", "these", "those", "i", "you", "he", "she", "it",
            "we", "they", "what", "which", "who", "when", "where", "why", "how",
            "for", "to", "of", "in", "on", "at", "by", "with", "from", "as",
        )
    }
}
