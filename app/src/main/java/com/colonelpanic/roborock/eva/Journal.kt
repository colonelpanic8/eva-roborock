package com.colonelpanic.roborock.eva

import org.json.JSONObject

/**
 * Write invocations keyed by EVA's invocation ID, recorded before anything is
 * sent so a replayed or orphaned ID never sends a command twice.
 */
class Journal(
    private val store: Store,
    private val now: () -> Long,
) {
    interface Store {
        fun read(): String?

        fun write(text: String)
    }

    data class Entry(
        val capability: String,
        val digest: String,
        val state: String,
        val envelope: String?,
    )

    @Synchronized
    fun get(id: String): Entry? =
        load().optJSONObject(id)?.let {
            Entry(it.getString("capability"), it.getString("digest"), it.getString("state"), it.optString("envelope").ifEmpty { null })
        }

    /** Records a pending submission; false when [id] already exists. */
    @Synchronized
    fun begin(
        id: String,
        capability: String,
        digest: String,
    ): Boolean {
        val all = load()
        if (all.has(id)) return false
        all.put(
            id,
            JSONObject()
                .put("capability", capability)
                .put("digest", digest)
                .put("state", "submitting")
                .put("at", now()),
        )
        save(all)
        return true
    }

    @Synchronized
    fun finish(
        id: String,
        state: String,
        envelope: String,
    ) {
        val all = load()
        val entry = all.optJSONObject(id) ?: return
        entry.put("state", state).put("envelope", envelope)
        save(all)
    }

    private fun load(): JSONObject =
        try {
            store.read()?.let { JSONObject(it) } ?: JSONObject()
        } catch (e: org.json.JSONException) {
            JSONObject()
        }

    private fun save(all: JSONObject) {
        val cutoff = now() - RETENTION_MILLIS
        all
            .keys()
            .asSequence()
            .toList()
            .forEach { if (all.getJSONObject(it).optLong("at") < cutoff) all.remove(it) }
        store.write(all.toString())
    }

    companion object {
        private const val RETENTION_MILLIS = 14L * 24 * 60 * 60 * 1000
    }
}
