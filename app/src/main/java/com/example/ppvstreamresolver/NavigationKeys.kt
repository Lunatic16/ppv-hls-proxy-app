package com.example.ppvstreamresolver

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object Main : NavKey
@Serializable data class Player(val iframeUrl: String, val title: String) : NavKey
