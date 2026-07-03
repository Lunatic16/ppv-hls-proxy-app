package com.example.ppvstreamresolver.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import com.example.ppvstreamresolver.Main
import com.example.ppvstreamresolver.Player
import com.example.ppvstreamresolver.data.DefaultDataRepository
import com.example.ppvstreamresolver.data.Event
import com.example.ppvstreamresolver.data.Source
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun MainScreen(
    onItemClick: (NavKey) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainScreenViewModel = viewModel { MainScreenViewModel(DefaultDataRepository()) },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var showSourceDialog by remember { mutableStateOf<Event?>(null) }
    var isSearchActive by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    Column(modifier = modifier.fillMaxSize()) {
        when (state) {
            MainScreenUiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is MainScreenUiState.Success -> {
                val events = (state as MainScreenUiState.Success).data
                val categories = remember(events) { events.map { it.category }.distinct().sorted() }

                ScrollableTabRow(
                    selectedTabIndex = categories.indexOf(selectedCategory).let { if (it == -1) 0 else it + 1 },
                    edgePadding = 0.dp,
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Tab(
                        selected = selectedCategory == null,
                        onClick = { selectedCategory = null },
                        text = { Text("All") }
                    )
                    categories.forEach { cat ->
                        Tab(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = cat },
                            text = { Text(cat) }
                        )
                    }
                }

                if (!isSearchActive) {
                    OutlinedCard(
                        onClick = { isSearchActive = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (searchQuery.isEmpty()) "Search events..." else searchQuery,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .focusRequester(focusRequester),
                        placeholder = { Text("Search events...") },
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = { 
                                isSearchActive = false
                            }) {
                                Text("Done")
                            }
                        }
                    )
                    LaunchedEffect(Unit) {
                        focusRequester.requestFocus()
                    }
                }

                val filteredEvents = remember(events, searchQuery, selectedCategory) {
                    events.filter {
                        (selectedCategory == null || it.category == selectedCategory) &&
                                (searchQuery.isEmpty() || it.name.contains(searchQuery, ignoreCase = true) ||
                                        it.sourceTag.contains(searchQuery, ignoreCase = true))
                    }
                }

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filteredEvents) { event ->
                        EventItem(
                            event = event,
                            onClick = { showSourceDialog = event }
                        )
                    }
                }
            }
            is MainScreenUiState.Error -> {
                Text(
                    text = "Error loading data: ${(state as MainScreenUiState.Error).throwable.message}",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }

    if (showSourceDialog != null) {
        val event = showSourceDialog!!
        AlertDialog(
            onDismissRequest = { showSourceDialog = null },
            title = { Text(event.name) },
            text = {
                Box(modifier = Modifier.heightIn(max = 400.dp)) {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(event.substreams) { source ->
                            ListItem(
                                headlineContent = { Text(source.label) },
                                supportingContent = { if (source.locale.isNotEmpty()) Text(source.locale) },
                                trailingContent = { if (source.isDefault) Badge { Text("default") } },
                                modifier = Modifier.clickable {
                                    showSourceDialog = null
                                    onItemClick(Player(source.iframeUrl, event.name))
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSourceDialog = null }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
fun EventItem(event: Event, onClick: () -> Unit) {
    val now = System.currentTimeMillis() / 1000
    val isLive = event.alwaysLive || (event.startsAt <= now && (event.endsAt == 0L || event.endsAt > now))
    val isSoon = !isLive && event.startsAt > now && event.startsAt - now < 86400

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isLive) {
                Badge(containerColor = Color.Red, contentColor = Color.White) {
                    Text(if (event.alwaysLive) "24/7" else "LIVE", modifier = Modifier.padding(horizontal = 4.dp))
                }
            } else if (isSoon) {
                Badge(containerColor = Color(0xFFFFA500), contentColor = Color.White) {
                    Text("SOON", modifier = Modifier.padding(horizontal = 4.dp))
                }
            }

            Column(modifier = Modifier.padding(start = 8.dp).weight(1f)) {
                Text(text = event.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(text = "${event.sourceTag} • ${event.category}", fontSize = 12.sp, color = Color.Gray)
            }

            Text(
                text = formatTime(event.startsAt),
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

fun formatTime(unixTs: Long): String {
    if (unixTs == 0L) return ""
    val date = Date(unixTs * 1000)
    val sdf = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
    return sdf.format(date)
}
