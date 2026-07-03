package com.example.ppvstreamresolver.data

import kotlinx.serialization.Serializable

@Serializable
data class StreamResponse(
    val success: Boolean,
    val streams: List<Category>? = null,
    val error: String? = null
)

@Serializable
data class Category(
    val category: String? = null,
    val category_name: String? = null,
    val streams: List<StreamItem>? = null
)

@Serializable
data class StreamItem(
    val id: Long? = null,
    val name: String? = null,
    val tag: String? = null,
    val source_tag: String? = null,
    val locale: String? = null,
    val uri_name: String? = null,
    val starts_at: Long? = null,
    val ends_at: Long? = null,
    val always_live: Int? = 0,
    val iframe: String? = null,
    val substreams: List<Substream>? = null
)

@Serializable
data class Substream(
    val source_tag: String? = null,
    val locale: String? = null,
    val iframe: String? = null
)

data class Event(
    val id: String,
    val name: String,
    val sourceTag: String,
    val locale: String,
    val category: String,
    val uri: String,
    val startsAt: Long,
    val endsAt: Long,
    val alwaysLive: Boolean,
    val iframe: String,
    val substreams: List<Source>
)

data class Source(
    val label: String,
    val locale: String,
    val iframeUrl: String,
    val isDefault: Boolean
)
