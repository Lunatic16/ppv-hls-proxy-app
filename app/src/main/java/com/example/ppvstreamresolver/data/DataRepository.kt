package com.example.ppvstreamresolver.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

interface DataRepository {
    val data: Flow<List<Event>>
}

class DefaultDataRepository : DataRepository {
    private val client = OkHttpClient.Builder().build()
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private val API_DOMAINS = listOf("ppv.st", "ppv.cx", "ppv.to", "ppv.is", "ppv.lc")

    override val data: Flow<List<Event>> = flow {
        val events = fetchEvents()
        emit(events)
    }

    private suspend fun fetchEvents(): List<Event> = withContext(Dispatchers.IO) {
        var lastError: String? = null
        for (domain in API_DOMAINS) {
            val url = "https://api.$domain/api/streams"
            Log.i("DataRepository", "Fetching from $url")
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        lastError = "HTTP ${response.code}"
                    } else {
                        val body = response.body?.string() ?: ""
                        val result = json.decodeFromString<StreamResponse>(body)
                        if (result.success && result.streams != null) {
                            Log.i("DataRepository", "Successfully fetched ${result.streams.size} categories")
                            return@withContext processStreams(result.streams)
                        } else {
                            lastError = result.error ?: "API returned success=false"
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("DataRepository", "Failed to fetch from $domain: ${e.message}")
                lastError = e.message
            }
        }
        throw Exception(lastError ?: "All API domains failed")
    }

    private fun processStreams(categories: List<Category>): List<Event> {
        Log.i("DataRepository", "Processing ${categories.size} categories")
        val allEvents = mutableListOf<Event>()
        for (cat in categories) {
            val catName = cat.category ?: cat.category_name ?: "Unknown"
            val streams = cat.streams ?: continue
            for (s in streams) {
                val substreams = mutableListOf<Source>()
                // Add default source
                if (s.iframe != null) {
                    substreams.add(
                        Source(
                            label = s.source_tag ?: "Default",
                            locale = s.locale ?: "",
                            iframeUrl = s.iframe,
                            isDefault = true
                        )
                    )
                }
                // Add substreams
                s.substreams?.forEach { sub ->
                    if (sub.iframe != null) {
                        substreams.add(
                            Source(
                                label = sub.source_tag ?: "Stream",
                                locale = sub.locale ?: "",
                                iframeUrl = sub.iframe,
                                isDefault = false
                            )
                        )
                    }
                }

                if (substreams.isNotEmpty()) {
                    allEvents.add(
                        Event(
                            id = s.id?.toString() ?: s.uri_name ?: "",
                            name = s.name ?: "Unknown",
                            sourceTag = s.source_tag ?: "",
                            locale = s.locale ?: "",
                            category = catName,
                            uri = s.uri_name ?: "",
                            startsAt = s.starts_at ?: 0L,
                            endsAt = s.ends_at ?: 0L,
                            alwaysLive = s.always_live == 1,
                            iframe = s.iframe ?: substreams.first().iframeUrl,
                            substreams = substreams
                        )
                    )
                }
            }
        }

        // Sort: LIVE first, SOON next, then by start time, 24/7 always at bottom
        return allEvents.sortedWith { a, b ->
            if (a.alwaysLive && !b.alwaysLive) return@sortedWith 1
            if (!a.alwaysLive && b.alwaysLive) return@sortedWith -1

            val now = System.currentTimeMillis() / 1000
            val aState = getEventState(a, now)
            val bState = getEventState(b, now)

            if (aState != bState) return@sortedWith aState - bState

            a.startsAt.compareTo(b.startsAt)
        }
    }

    private fun getEventState(ev: Event, now: Long): Int {
        return when {
            ev.alwaysLive -> 0
            ev.endsAt > 0 && ev.endsAt < now -> 3 // Ended
            ev.startsAt <= now -> 0 // Live
            ev.startsAt - now < 86400 -> 1 // Soon
            else -> 2 // Info
        }
    }
}
