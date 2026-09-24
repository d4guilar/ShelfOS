// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import com.d4guilar.shelfos.R
import com.d4guilar.shelfos.core.designsystem.ShelfIcon
import com.d4guilar.shelfos.core.designsystem.shelfAction
import com.d4guilar.shelfos.core.input.InputContext
import com.d4guilar.shelfos.core.input.ShelfCommand
import com.d4guilar.shelfos.core.input.shelfCommand
import com.d4guilar.shelfos.core.theme.LocalShelfTokens
import com.d4guilar.shelfos.core.theme.ThemeId
import com.d4guilar.shelfos.feature.library.*
import com.d4guilar.shelfos.feature.settings.SettingsScreen
import com.d4guilar.shelfos.feature.settings.SettingsViewModel

enum class Destination(val route: String, val label: String, val icon: ShelfIcon) {
    LIBRARY("library", "Library", ShelfIcon.LIBRARY), SEARCH("search", "Search", ShelfIcon.SEARCH),
    NOTES("notes", "Notes", ShelfIcon.NOTES), COLLECTIONS("collections", "Collections", ShelfIcon.COLLECTIONS),
    SETTINGS("settings", "Settings", ShelfIcon.SETTINGS),
}

@Composable
fun ShelfApp(library: LibraryViewModel, settings: SettingsViewModel, onSystemBack: () -> Unit) {
    val t = LocalShelfTokens.current
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: Destination.LIBRARY.route
    val libraryState by library.state.collectAsStateWithLifecycle()
    val theme by settings.theme.collectAsStateWithLifecycle()
    val error by settings.error.collectAsStateWithLifecycle()
    fun navigate(destination: Destination) {
        nav.navigate(destination.route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(t.colors.canvas).onPreviewKeyEvent { event ->
        val command = event.nativeKeyEvent.shelfCommand(InputContext.LIBRARY)
        when (command) {
            ShelfCommand.SEARCH, ShelfCommand.BACK -> {
                if (event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_UP) {
                    if (command == ShelfCommand.SEARCH) navigate(Destination.SEARCH)
                    else if (!nav.popBackStack()) onSystemBack()
                }
                true
            }
            else -> false
        }
    }) {
        val layout = adaptiveLayout(maxWidth.value, maxHeight.value)
        Row(Modifier.fillMaxSize()) {
            if (layout.navigation == NavigationLayout.RAIL) {
                Column(Modifier.width(88.dp).fillMaxHeight().verticalScroll(rememberScrollState()).testTag("navigation_rail")) {
                    Spacer(Modifier.height(t.spacing.medium))
                    Destination.entries.forEach { destination ->
                        NavigationItem(destination, route == destination.route || (destination == Destination.LIBRARY && route.startsWith("details")),
                            Modifier.fillMaxWidth(), onClick = { navigate(destination) })
                    }
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(t.colors.divider))
            }
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = t.spacing.medium, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (route.startsWith("details")) {
                        Text("Back", Modifier.shelfAction(onClick = { nav.popBackStack() }).padding(12.dp))
                    } else {
                        Icon(painterResource(R.drawable.ic_shelf), contentDescription = null, modifier = Modifier.size(30.dp))
                        Text(buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("Shelf") }
                            withStyle(SpanStyle(fontWeight = FontWeight.Light)) { append("OS") }
                        }, style = MaterialTheme.typography.headlineMedium)
                    }
                    Spacer(Modifier.weight(1f))
                    Text("Prototype", color = t.colors.secondary, style = MaterialTheme.typography.labelSmall)
                }
                HorizontalDivider(color = t.colors.divider)
                NavHost(navController = nav, startDestination = Destination.LIBRARY.route, modifier = Modifier.weight(1f)) {
                    composable(Destination.LIBRARY.route) {
                        LibraryScreen(libraryState, layout.showDetails, library::selectFilter, library::select,
                            onOpen = { id -> library.select(id); if (!layout.showDetails) nav.navigate("details/$id") },
                            onFavorite = library::toggleFavorite)
                    }
                    composable("details/{id}") { backStack ->
                        val item = library.publication(backStack.arguments?.getString("id"))
                        if (item != null) PublicationDetails(item, item.id in libraryState.favorites,
                            onFavorite = { library.toggleFavorite(item.id) }, modifier = Modifier.fillMaxWidth().testTag("details_screen"))
                        else PlaceholderScreen("Publication unavailable", "Return to the Library to choose a sample.")
                    }
                    composable(Destination.SEARCH.route) {
                        Column(Modifier.fillMaxSize().padding(t.spacing.medium), verticalArrangement = Arrangement.spacedBy(t.spacing.medium)) {
                            Text("Search", style = MaterialTheme.typography.headlineMedium)
                            OutlinedTextField(libraryState.query, library::search,
                                label = { Text("Search sample titles or creators") }, singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("search_field"))
                            val results = library.searchResults(libraryState.query)
                            Text("${results.size} sample results", color = t.colors.secondary)
                            LazyColumn {
                                items(results, key = { it.id }) { item ->
                                    Row(Modifier.fillMaxWidth().shelfAction(onClick = {
                                        library.select(item.id); nav.navigate("details/${item.id}")
                                    }).padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        PublicationCover(item, Modifier.width(52.dp))
                                        Column { Text(item.title); Text(item.creator, color = t.colors.secondary) }
                                    }
                                }
                            }
                        }
                    }
                    composable(Destination.NOTES.route) { PlaceholderScreen("Notes", "A place for your reading notes. Notes and annotations are planned for a later phase.") }
                    composable(Destination.COLLECTIONS.route) { PlaceholderScreen("Collections", "Your own ways to organize a library. Collections are planned for a later phase.") }
                    composable(Destination.SETTINGS.route) { SettingsScreen(theme ?: ThemeId.CLASSIC, error, settings::select) }
                }
                if (layout.navigation == NavigationLayout.BOTTOM) {
                    HorizontalDivider(color = t.colors.divider)
                    Row(Modifier.fillMaxWidth().testTag("bottom_navigation")) {
                        Destination.entries.forEach { destination ->
                            NavigationItem(destination, route == destination.route || (destination == Destination.LIBRARY && route.startsWith("details")),
                                Modifier.weight(1f), onClick = { navigate(destination) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavigationItem(destination: Destination, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val t = LocalShelfTokens.current
    Column(modifier.testTag("nav_${destination.route}")
        .background(if (selected) t.colors.muted else t.colors.canvas)
        .shelfAction(selected = selected, role = Role.Tab, onClick = onClick)
        .padding(horizontal = 2.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ShelfIcon(destination.icon)
        Text(destination.label, style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun PlaceholderScreen(title: String, description: String) {
    val t = LocalShelfTokens.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(t.spacing.large),
        verticalArrangement = Arrangement.spacedBy(t.spacing.medium)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(description, color = t.colors.secondary)
    }
}
