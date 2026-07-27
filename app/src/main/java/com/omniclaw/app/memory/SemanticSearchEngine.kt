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
        // V-M12: invalidate the IDF cache — adding a document changes the
        // document frequency of every term it contains, so cached IDFs are
        // now stale. Existing document embeddings were computed with the
        // old IDFs and are an approximation; for a memory-index of <10k
        // entries the drift is acceptable.
        invalidateIdfCache()
    }

    /** Remove a document from the index. */
    fun remove(docId: String) {
        documents.remove(docId)
        embeddingCache.remove(docId)
        invalidateIdfCache()
    }

    /**
     * Search the index for [query], returning the top [k] doc IDs by
     * cosine similarity to the query embedding.
     *
     * V-M12: both the query embedding AND each document's embedding are
     * (re)computed here using the CURRENT corpus state, so IDF weights
     * reflect the latest document set. The `embedding` field cached on
     * each [DocEntry] is computed at index time and goes stale as soon
     * as the next document is added — using it would zero-out scores
     * for the first document in a fresh corpus (its terms all have df=N=1
     * → idf=ln(2/2)=0). Recomputing per-search is O(n × dims) and cheap
     * for memory indices of <10k entries.
     */
    fun search(query: String, k: Int = 5): List<SearchResult> {
        val queryTokens = tokenize(query)
        val queryTf = computeTf(queryTokens)
        val queryEmbedding = buildEmbedding(queryTf)

        return documents.values
            .map { doc ->
                val docEmbedding = buildEmbedding(doc.tf)
                SearchResult(
                    docId = doc.id,
                    content = doc.content,
                    score = cosineSimilarity(queryEmbedding, docEmbedding),
                )
            }
            .sortedByDescending { it.score }
            .take(k)
    }

    /**
     * V-M11: batched search — runs [search] for each query and returns the
     * per-query top-k results. Provided as a convenience for callers that
     * need to score multiple queries against the same index (e.g. retrieving
     * context for several candidate agent actions at once).
     *
     * NOTE: this is still O(n × |queries|). For production scale (>10k docs
     * or >100 queries per turn), integrate an ANN index (FAISS, HNSW) keyed
     * by the same embedding space — the [EmbeddingCache] already holds the
     * per-doc vectors needed to seed such an index.
     */
    fun searchBatch(queries: List<String>, k: Int = 5): List<List<SearchResult>> =
        queries.map { q -> search(q, k) }

    /** Clear the entire index. */
    fun clear() {
        documents.clear()
        embeddingCache.clear()
        invalidateIdfCache()
    }

    val size: Int get() = documents.size

    // ---- Internal ----

    // V-M12: per-term IDF cache. TF alone treats common words (e.g. "photo")
    // with the same weight as rare words (e.g. "parrot"); multiplying by IDF
    // down-weights terms that appear in most documents so they contribute
    // less to the cosine similarity. The cache is invalidated on index/remove.
    private val idfLock = Any()
    private val idfCache = mutableMapOf<String, Float>()

    private fun invalidateIdfCache() = synchronized(idfLock) { idfCache.clear() }

    private fun idf(term: String): Float = synchronized(idfLock) {
        idfCache.getOrPut(term) {
            val df = documents.values.count { doc -> term in doc.tf }
            if (df == 0) 0f else kotlin.math.ln((documents.size + 1f) / (df + 1f))
        }
    }

    private fun tokenize(text: String): List<String> {
        // V-M10: use Unicode-aware character classes (\p{L} letters, \p{N}
        // digits) instead of [a-z0-9] so non-ASCII content (CJK, accented
        // Latin, Cyrillic, etc.) survives tokenization. The previous regex
        // would strip every non-ASCII char to a space, losing e.g. "café" →
        // "caf " and Chinese text entirely.
        return text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
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
     *
     * V-M12: weights are now TF×IDF, not plain TF. This down-weights terms
     * that appear in most documents so they contribute less to cosine
     * similarity — the class KDoc claims TF-IDF and now it actually is.
     */
    private fun buildEmbedding(tf: Map<String, Float>, dims: Int = 256): FloatArray {
        val vec = FloatArray(dims)
        for ((word, weight) in tf) {
            val tfidf = weight * idf(word)
            if (tfidf == 0f) continue
            val hash = word.hashCode()
            for (d in 0 until dims) {
                val proj = ((hash shr (d % 32)) and 1) - 0.5f
                vec[d] += proj * tfidf
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
