package com.gangg111.sklep

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private const val PUBLISHER = "DK-LAB"

// Kolory zmierzone ze zrzutów Google Play (ciemny) i jego jasny odpowiednik.
private val PlayDark = darkColorScheme(
    primary = Color(0xFFA8C7FA), onPrimary = Color(0xFF062E6F),
    secondaryContainer = Color(0xFF004A77), onSecondaryContainer = Color(0xFFC2E7FF),
    background = Color(0xFF131313), surface = Color(0xFF131313),
    onBackground = Color(0xFFE3E3E3), onSurface = Color(0xFFE3E3E3), onSurfaceVariant = Color(0xFFC4C7C5),
    surfaceContainerLow = Color(0xFF1B1B1B), surfaceContainer = Color(0xFF1E1F21),
    surfaceContainerHigh = Color(0xFF292A2C), surfaceVariant = Color(0xFF1E1F21),
    surfaceContainerHighest = Color(0xFF3A3A3C), surfaceContainerLowest = Color.Black,
    outline = Color(0xFF8E918F), outlineVariant = Color(0xFF444746),
)
private val PlayLight = lightColorScheme(
    primary = Color(0xFF0B57D0), onPrimary = Color.White,
    secondaryContainer = Color(0xFFC2E7FF), onSecondaryContainer = Color(0xFF001D35),
    background = Color.White, surface = Color.White,
    onBackground = Color(0xFF1F1F1F), onSurface = Color(0xFF1F1F1F), onSurfaceVariant = Color(0xFF444746),
    surfaceContainerLow = Color(0xFFF8FAFD), surfaceContainer = Color(0xFFF0F4F9),
    surfaceContainerHigh = Color(0xFFE9EEF6), surfaceVariant = Color(0xFFF0F4F9),
    surfaceContainerHighest = Color(0xFFDDE3EA), surfaceContainerLowest = Color.White,
    outline = Color(0xFF747775), outlineVariant = Color(0xFFC4C7C5),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Store.apps.value.isEmpty()) Store.refresh(this)
        UpdateCheckJob.schedule(this)
        if (Build.VERSION.SDK_INT >= 33 && Store.prefs(this).getBoolean("notify", true) &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        handle(intent)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) PlayDark else PlayLight) { StoreApp() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** Powiadomienie o aktualizacji otwiera stronę danej aplikacji. */
    private fun handle(intent: Intent?) {
        intent?.getStringExtra("open")?.let { Store.openRequest.value = it }
    }

    override fun onResume() {
        super.onResume()
        // Instalacja/odinstalowanie dzieje się w systemowym oknie, po powrocie przelicz przyciski.
        Store.installedTick.value++
    }
}

private enum class State { NotInstalled, Update, Installed }
private enum class Tab { Apps, Search, You }

private fun stateOf(ctx: Context, a: AppEntry): State {
    val inst = Store.installedVersion(ctx, a.pkg) ?: return State.NotInstalled
    val rel = Store.releases[a.pkg] ?: return State.Installed
    return if (Store.isNewer(rel, inst)) State.Update else State.Installed
}

/** Stany wszystkich aplikacji, przeliczane po powrocie do apki i po wczytaniu wydań. */
@Composable
private fun states(): Map<String, State> {
    val ctx = LocalContext.current
    val tick by Store.installedTick
    val apps = Store.apps.value
    return remember(apps, tick, Store.releases.toMap()) { apps.associate { it.pkg to stateOf(ctx, it) } }
}

private fun mb(bytes: Long) = if (bytes >= 1_000_000_000) "%.1f GB".format(bytes / 1e9) else "%.0f MB".format(maxOf(1.0, bytes / 1e6))
private fun percent(done: Long, total: Long) = if (total > 0) (done * 100 / total).toInt() else 0

@Composable
private fun StoreApp() {
    var tab by rememberSaveable { mutableStateOf(Tab.Apps) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val snack = remember { SnackbarHostState() }
    val msg by Store.message
    LaunchedEffect(msg) {
        msg?.let { snack.showSnackbar(it); Store.message.value = null }
    }
    val req by Store.openRequest
    LaunchedEffect(req) {
        req?.let { open = it; Store.openRequest.value = null }
    }
    val app = Store.apps.value.firstOrNull { it.pkg == open }
    BackHandler(app != null || tab != Tab.Apps) { if (app != null) open = null else tab = Tab.Apps }
    val ctx = LocalContext.current
    val updates = states().count { it.value == State.Update && it.key != ctx.packageName }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                NavItem(tab == Tab.Apps, Icons.Default.Home, "Aplikacje") { tab = Tab.Apps; open = null }
                NavItem(tab == Tab.Search, Icons.Default.Search, "Szukaj") { tab = Tab.Search; open = null }
                NavItem(tab == Tab.You, Icons.Default.Person, "Ty", badge = updates) { tab = Tab.You; open = null }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                val onOpen: (String) -> Unit = { open = it }
                when {
                    app != null -> DetailScreen(app, onOpen) { open = null }
                    tab == Tab.Apps -> HomeScreen(onOpen)
                    tab == Tab.Search -> SearchScreen(onOpen)
                    else -> YouScreen(onOpen)
                }
            }
        }
    }
}

@Composable
private fun RowScope.NavItem(selected: Boolean, icon: ImageVector, label: String, badge: Int = 0, onClick: () -> Unit) =
    NavigationBarItem(
        selected = selected, onClick = onClick, label = { Text(label) },
        icon = {
            BadgedBox(badge = { if (badge > 0) Badge { Text("$badge") } }) { Icon(icon, label) }
        },
        colors = NavigationBarItemDefaults.colors(
            selectedTextColor = MaterialTheme.colorScheme.primary,
            indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
            selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    )

// ---------------- ekran główny ----------------

@Composable
private fun HomeScreen(onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val st = states()
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    val all = listed(ctx)
    val self = Store.apps.value.firstOrNull { it.pkg == ctx.packageName }
    val apps = all.filter { category == null || it.category == category }
    val updates = apps.filter { st[it.pkg] == State.Update }
    // Wyróżniona: aplikacja z najświeższym wydaniem.
    val featured = all.maxByOrNull { Store.releases[it.pkg]?.date ?: "" }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.padding(16.dp, 12.dp, 8.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.logo), null, Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)))
                Text("LABstore", Modifier.padding(start = 12.dp).weight(1f), fontSize = 22.sp)
                IconButton({ Store.refresh(ctx) }) { Icon(Icons.Default.Refresh, "Odśwież") }
            }
        }
        item {
            val cats = all.map { it.category }.filter { it.isNotEmpty() }.distinct()
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { PlayChip("Wszystkie", category == null) { category = null } }
                items(cats) { c -> PlayChip(c, category == c) { category = if (category == c) null else c } }
            }
        }
        if (self != null && (st[self.pkg] == State.Update || Store.jobs[self.pkg] != null)) item { SelfUpdateBanner(self) }
        if (Store.loading.value) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp, 12.dp, 16.dp, 0.dp)) }
        if (Store.offline.value) item {
            Text(
                "Brak połączenia z katalogiem online, pokazuję listę wbudowaną w aplikację.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 0.dp),
            )
        }
        if (category == null && featured != null) item { FeaturedCard(featured, st[featured.pkg]!!, onOpen) }
        if (updates.isNotEmpty()) {
            item { SectionHeader("Dostępne aktualizacje") }
            items(updates, key = { "u" + it.pkg }) { AppRow(it, st[it.pkg]!!, onOpen) }
        }
        item { SectionHeader(category ?: "Wszystkie aplikacje") }
        items(apps, key = { it.pkg }) { AppRow(it, st[it.pkg]!!, onOpen) }
    }
}

/** Aplikacje do pokazania na listach: sam LABstore jest w katalogu tylko po to, żeby się aktualizował. */
private fun listed(ctx: Context) = Store.apps.value.filter { it.pkg != ctx.packageName }

@Composable
private fun SelfUpdateBanner(self: AppEntry) {
    val c = MaterialTheme.colorScheme
    val rel = Store.releases[self.pkg]
    Row(
        Modifier.padding(16.dp, 16.dp, 16.dp, 0.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(c.secondaryContainer).padding(16.dp, 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text("Dostępna nowa wersja LABstore", color = c.onSecondaryContainer, style = MaterialTheme.typography.titleMedium)
            rel?.let { Text("${it.tag}  •  ${mb(it.size)}", color = c.onSecondaryContainer, style = MaterialTheme.typography.bodyMedium) }
        }
        SmallAction(self, State.Update) {}
    }
}

@Composable
private fun PlayChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.clip(RoundedCornerShape(16.dp))
            .background(if (selected) c.secondaryContainer else c.surfaceContainer)
            .clickable(onClick = onClick).padding(16.dp, 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) Icon(Icons.Default.Check, null, Modifier.size(18.dp).padding(end = 4.dp), tint = c.onSecondaryContainer)
        Text(text, color = if (selected) c.onSecondaryContainer else c.onSurface, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun FeaturedCard(a: AppEntry, state: State, onOpen: (String) -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier.padding(16.dp, 20.dp, 16.dp, 4.dp).clip(RoundedCornerShape(24.dp))
            .background(c.surfaceContainerLow).clickable { onOpen(a.pkg) },
    ) {
        Box(Modifier.fillMaxWidth().height(190.dp)) {
            a.screenshots.firstOrNull()?.let { NetImage(it, 600.dp, Modifier.fillMaxSize(), ContentScale.Crop, Alignment.TopCenter) }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))))
            Text(
                "Najnowsze wydanie: ${Store.releases[a.pkg]?.tag ?: ""}", Modifier.align(Alignment.BottomStart).padding(20.dp, 16.dp),
                color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium,
            )
        }
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(a, 56.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(a.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(PUBLISHER, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            }
            SmallAction(a, state, onOpen)
        }
    }
}

/** Przycisk w wierszu/karcie: instaluje od razu jak w Play, „Otwórz” dla zainstalowanych. */
@Composable
private fun SmallAction(a: AppEntry, state: State, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val install = installAction(a)
    when (val task = Store.jobs[a.pkg]) {
        null -> {}
        is Task.Paused -> {
            Button({ resume(ctx, a, task) }, contentPadding = PaddingValues(20.dp, 8.dp)) { Text("Wznów") }
            return
        }
        else -> {
            CircularProgressIndicator(Modifier.size(28.dp).clickable { onOpen(a.pkg) }, strokeWidth = 3.dp)
            return
        }
    }
    val (label, action) = when (state) {
        State.NotInstalled -> "Zainstaluj" to install
        State.Update -> "Aktualizuj" to install
        State.Installed -> {
            if (a.pkg == ctx.packageName) return
            "Otwórz" to { ctx.packageManager.getLaunchIntentForPackage(a.pkg)?.let { ctx.startActivity(it) }; Unit }
        }
    }
    Button(action, contentPadding = PaddingValues(20.dp, 8.dp)) { Text(label) }
}

@Composable
private fun SectionHeader(text: String, onArrow: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(16.dp, 24.dp, 16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Medium)
        if (onArrow != null) ArrowCircle(Icons.AutoMirrored.Filled.ArrowForward, onArrow)
    }
}

@Composable
private fun ArrowCircle(icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(22.dp)) }
}

@Composable
private fun AppRow(a: AppEntry, state: State, onOpen: (String) -> Unit, trailing: @Composable (() -> Unit)? = null) {
    val c = MaterialTheme.colorScheme
    val rel = Store.releases[a.pkg]
    val task = Store.jobs[a.pkg]
    Row(
        Modifier.fillMaxWidth().clickable { onOpen(a.pkg) }.padding(16.dp, 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(a, 64.dp)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(a.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(PUBLISHER, a.category).filter { it.isNotEmpty() }.joinToString("  •  "),
                style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    task is Task.Downloading -> Pill("Pobieranie ${percent(task.done, task.total)}%")
                    task is Task.Paused -> Pill("Wstrzymano ${percent(task.done, task.total)}%")
                    task is Task.Verifying -> Pill("Sprawdzanie…")
                    task is Task.Installing -> Pill("Instalowanie…")
                    else -> {
                        rel?.let { Pill(it.tag) }
                        when (state) {
                            State.Installed -> Pill("✓ Zainstalowano")
                            State.Update -> Pill("↑ Aktualizacja")
                            State.NotInstalled -> rel?.let { Pill("↓ ${mb(it.size)}") }
                        }
                    }
                }
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun Pill(text: String) = Text(
    text, Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(8.dp, 2.dp),
    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
private fun AppIcon(a: AppEntry, size: Dp) {
    val shape = RoundedCornerShape(size * 0.22f)
    Box(
        Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceContainer),
        contentAlignment = Alignment.Center,
    ) {
        NetImage(a.icon, size, Modifier.fillMaxSize(), ContentScale.Crop) {
            Text(a.name.take(1), fontSize = (size.value / 2).sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun NetImage(
    src: String, size: Dp, modifier: Modifier, scale: ContentScale, align: Alignment = Alignment.Center,
    placeholder: @Composable () -> Unit = {},
) {
    val ctx = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bmp by produceState<android.graphics.Bitmap?>(null, src) { value = Store.image(ctx, src, px) }
    bmp?.let { Image(it.asImageBitmap(), null, modifier, alignment = align, contentScale = scale) } ?: placeholder()
}

// ---------------- szukaj i „Ty” ----------------

@Composable
private fun SearchScreen(onOpen: (String) -> Unit) {
    val st = states()
    var query by rememberSaveable { mutableStateOf("") }
    val apps = listed(LocalContext.current).filter {
        query.isBlank() || it.name.contains(query, true) || it.summary.contains(query, true) || it.category.contains(query, true)
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            TextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text("Szukaj aplikacji") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Default.Clear, "Wyczyść") } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                modifier = Modifier.fillMaxWidth().padding(16.dp, 12.dp),
            )
        }
        items(apps, key = { it.pkg }) { AppRow(it, st[it.pkg]!!, onOpen) }
        if (apps.isEmpty()) item {
            Text("Nic nie znaleziono", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private enum class YouPage { Menu, Manage, Updates, Settings }

/** Zakładka „Ty” na wzór menu konta Google Play i ekranu „Zarządzaj aplikacjami”. */
@Composable
private fun YouScreen(onOpen: (String) -> Unit) {
    var page by rememberSaveable { mutableStateOf(YouPage.Menu) }
    BackHandler(page != YouPage.Menu) { page = if (page == YouPage.Updates) YouPage.Manage else YouPage.Menu }
    when (page) {
        YouPage.Menu -> YouMenu { page = it }
        YouPage.Manage -> ManageScreen(onOpen, { page = YouPage.Menu }) { page = YouPage.Updates }
        YouPage.Updates -> UpdatesScreen(onOpen) { page = YouPage.Manage }
        YouPage.Settings -> SettingsScreen { page = YouPage.Menu }
    }
}

@Composable
private fun YouMenu(go: (YouPage) -> Unit) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    val st = states()
    val updates = listed(ctx).count { st[it.pkg] == State.Update }
    val version = remember { Store.installedVersion(ctx, ctx.packageName)?.second ?: "" }
    val open = { url: String -> ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    Column(
        Modifier.fillMaxSize().background(c.surfaceContainerHighest).verticalScroll(rememberScrollState())
            .padding(16.dp, 16.dp, 16.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(c.surfaceContainerLowest).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(painterResource(R.drawable.logo), null, Modifier.size(56.dp).clip(CircleShape))
            Column(Modifier.padding(start = 16.dp)) {
                Text("LABstore", fontSize = 22.sp, fontWeight = FontWeight.Medium)
                Text("$PUBLISHER  •  wersja $version", color = c.onSurfaceVariant)
            }
        }
        MenuGroup(
            listOf(
                MenuItem(Icons.Default.List, "Zarządzaj aplikacjami", if (updates > 0) "$updates" else null) { go(YouPage.Manage) },
                MenuItem(Icons.Default.Notifications, "Powiadomienia") {
                    ctx.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName),
                    )
                },
                MenuItem(Icons.Default.Lock, "Bezpieczeństwo", "SHA-256") {
                    Store.message.value = "Każdy plik APK jest sprawdzany sumą SHA-256 z GitHuba przed instalacją"
                },
            ),
        )
        MenuGroup(
            listOf(
                MenuItem(Icons.Default.Settings, "Ustawienia") { go(YouPage.Settings) },
                MenuItem(Icons.Default.Info, "Pomoc i opinie") { open("https://github.com/gangg111/Sklep/issues") },
            ),
        )
        Text(
            "O LABstore", Modifier.align(Alignment.CenterHorizontally).clickable { open("https://github.com/gangg111/Sklep") }
                .padding(12.dp),
            color = c.onSurface,
        )
    }
}

private class MenuItem(val icon: ImageVector, val title: String, val trailing: String? = null, val onClick: () -> Unit)

/** Grupa wierszy jak w menu Play: jedna zaokrąglona karta, wiersze rozdzielone cienką przerwą w kolorze tła. */
@Composable
private fun MenuGroup(items: List<MenuItem>) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        items.forEach { m ->
            Row(
                Modifier.fillMaxWidth().background(c.surfaceContainerLowest).clickable(onClick = m.onClick).padding(20.dp, 22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(m.icon, null, Modifier.size(26.dp))
                Text(m.title, Modifier.weight(1f).padding(start = 20.dp), fontSize = 18.sp)
                m.trailing?.let { Text(it, color = c.onSurface) }
            }
        }
    }
}

@Composable
private fun SubTopBar(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(4.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Wstecz") }
        Text(title, Modifier.padding(start = 12.dp), fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun updateAll(ctx: Context, apps: List<AppEntry>) =
    apps.forEach { a -> if (Store.jobs[a.pkg] == null) Store.releases[a.pkg]?.let { requestInstall(ctx, a, it) } }

@Composable
private fun ManageScreen(onOpen: (String) -> Unit, onBack: () -> Unit, onUpdates: () -> Unit) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    val st = states()
    val installed = listed(ctx).filter { st[it.pkg] != State.NotInstalled }
    val updates = installed.filter { st[it.pkg] == State.Update }
    var tab by rememberSaveable { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        SubTopBar("Zarządzaj aplikacjami", onBack)
        TabRow(
            tab, containerColor = c.background,
            indicator = { pos ->
                with(TabRowDefaults) {
                    PrimaryIndicator(Modifier.tabIndicatorOffset(pos[tab]), width = 64.dp, color = c.primary)
                }
            },
        ) {
            listOf("Przegląd", "Zarządzaj").forEachIndexed { i, t ->
                Tab(tab == i, { tab = i }, text = { Text(t, fontSize = 16.sp) }, unselectedContentColor = c.onSurfaceVariant)
            }
        }
        if (tab == 0) Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
            OverviewRow(Icons.Default.Lock, "Weryfikacja plików włączona", "Każde APK sprawdzane sumą SHA-256 przed instalacją")
            OverviewRow(
                Icons.Default.Refresh,
                if (updates.isEmpty()) "Aplikacje są aktualne" else "Dostępne aktualizacje",
                if (updates.isEmpty()) "Brak oczekujących aktualizacji" else "${updates.size} ${plural(updates.size)}",
            ) {
                if (updates.isNotEmpty()) Row(Modifier.padding(top = 4.dp)) {
                    TextButton({ updateAll(ctx, updates) }) { Text("Aktualizuj wszystkie", fontSize = 16.sp) }
                    TextButton(onUpdates) { Text("Zobacz szczegóły", fontSize = 16.sp) }
                }
            }
            val (used, total) = remember {
                val f = android.os.StatFs(android.os.Environment.getDataDirectory().path)
                (f.totalBytes - f.availableBytes) to f.totalBytes
            }
            OverviewRow(Icons.Default.Info, "Zajęte ${gb(used)} z ${gb(total)}", null) {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp, end = 4.dp).height(4.dp)) {
                    val frac = used.toFloat() / total
                    Box(Modifier.weight(frac).fillMaxHeight().clip(CircleShape).background(c.primary))
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.weight(1 - frac).fillMaxHeight().clip(CircleShape).background(c.surfaceContainerHigh))
                }
            }
            OverviewRow(Icons.Default.Home, "Zainstalowane z katalogu", "${installed.size} z ${listed(ctx).size} aplikacji")
        } else LazyColumn {
            items(installed, key = { it.pkg }) { a -> AppRow(a, st[a.pkg]!!, onOpen) { SmallAction(a, st[a.pkg]!!, onOpen) } }
            if (installed.isEmpty()) item {
                Text("Nie masz jeszcze aplikacji z LABstore", Modifier.padding(20.dp), color = c.onSurfaceVariant)
            }
        }
    }
}

private fun plural(n: Int) = when {
    n == 1 -> "oczekująca aktualizacja"
    n % 10 in 2..4 && n % 100 !in 12..14 -> "oczekujące aktualizacje"
    else -> "oczekujących aktualizacji"
}

private fun gb(b: Long) = if (b >= 1_000_000_000_000) "%.1f TB".format(b / 1e12) else "%.0f GB".format(b / 1e9)

@Composable
private fun OverviewRow(icon: ImageVector, title: String, sub: String?, extra: @Composable ColumnScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(20.dp, 14.dp)) {
        Icon(icon, null, Modifier.padding(top = 2.dp).size(26.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.padding(start = 28.dp)) {
            Text(title, fontSize = 18.sp)
            sub?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge) }
            extra()
        }
    }
}

/** „Pobrane” z Play: lista aktualizacji, rozwijane notatki wydania, „Zaktualizuj” przy każdej. */
@Composable
private fun UpdatesScreen(onOpen: (String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    val st = states()
    val updates = listed(ctx).filter { st[it.pkg] == State.Update || Store.jobs[it.pkg] != null }
    Column(Modifier.fillMaxSize()) {
        SubTopBar("Aktualizacje", onBack)
        Row(Modifier.padding(20.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Dostępne aktualizacje", Modifier.weight(1f), fontSize = 20.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (updates.isNotEmpty()) Button({ updateAll(ctx, updates) }, Modifier.height(44.dp)) {
                Text("Aktualizuj wszystkie", fontSize = 16.sp)
            }
        }
        if (updates.isEmpty()) Text("Wszystkie aplikacje są aktualne", Modifier.padding(20.dp), color = c.onSurfaceVariant)
        LazyColumn {
            items(updates, key = { it.pkg }) { a ->
                var expanded by rememberSaveable(a.pkg) { mutableStateOf(false) }
                val rel = Store.releases[a.pkg]
                val task = Store.jobs[a.pkg]
                Column(Modifier.fillMaxWidth().clickable { onOpen(a.pkg) }.padding(20.dp, 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(a, 48.dp)
                        Column(Modifier.weight(1f).padding(start = 16.dp)) {
                            Text(a.name, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                when (task) {
                                    is Task.Downloading -> "${percent(task.done, task.total)}% z ${mb(task.total)}"
                                    is Task.Paused -> "Wstrzymano ${percent(task.done, task.total)}%"
                                    Task.Verifying -> "Sprawdzanie…"
                                    Task.Installing -> "Instalowanie…"
                                    null -> listOfNotNull(rel?.tag, rel?.let { mb(it.size) }).joinToString("  •  ")
                                },
                                color = c.onSurfaceVariant,
                            )
                        }
                        IconButton({ expanded = !expanded }) {
                            Icon(
                                Icons.Default.KeyboardArrowDown, if (expanded) "Zwiń" else "Co nowego",
                                Modifier.graphicsLayer { rotationZ = if (expanded) 180f else 0f },
                            )
                        }
                        when (task) {
                            null -> OutlinedButton({ rel?.let { requestInstall(ctx, a, it) } }) { Text("Zaktualizuj") }
                            is Task.Paused -> OutlinedButton({ resume(ctx, a, task) }) { Text("Wznów") }
                            else -> CircularProgressIndicator(Modifier.padding(horizontal = 16.dp).size(28.dp), strokeWidth = 3.dp)
                        }
                    }
                    if (expanded) Text(
                        rel?.notes?.takeIf { it.isNotBlank() }?.let { plain(it) } ?: "Brak opisu zmian",
                        Modifier.padding(start = 64.dp, top = 8.dp), color = c.onSurfaceVariant,
                        maxLines = 10, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = Store.prefs(ctx)
    var notify by remember { mutableStateOf(prefs.getBoolean("notify", true)) }
    var beta by remember { mutableStateOf(prefs.getBoolean("beta", false)) }
    Column(Modifier.fillMaxSize()) {
        SubTopBar("Ustawienia", onBack)
        SettingSwitch("Powiadamiaj o aktualizacjach", "Sprawdzanie w tle co kilka godzin", notify) {
            notify = it
            prefs.edit().putBoolean("notify", it).apply()
            UpdateCheckJob.schedule(ctx)
        }
        SettingSwitch("Pokaż wersje testowe", "Wydania oznaczone na GitHubie jako pre-release", beta) {
            beta = it
            prefs.edit().putBoolean("beta", it).apply()
            Store.refresh(ctx)
        }
    }
}

// ---------------- strona aplikacji ----------------

@Composable
private fun DetailScreen(a: AppEntry, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    val st = states()
    val state = st[a.pkg] ?: State.NotInstalled
    val tick by Store.installedTick
    val installed = remember(tick) { Store.installedVersion(ctx, a.pkg) }
    val rel = Store.releases[a.pkg]
    val task = Store.jobs[a.pkg]
    var aboutOpen by rememberSaveable(a.pkg) { mutableStateOf(false) }
    var notesOpen by rememberSaveable(a.pkg) { mutableStateOf(false) }
    var historyOpen by rememberSaveable(a.pkg) { mutableStateOf(false) }
    var viewer by remember(a.pkg) { mutableStateOf<Int?>(null) }
    viewer?.let { ScreenshotViewer(a.screenshots, it) { viewer = null } }
    val repoUrl = "https://github.com/${a.repo}"

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Wstecz") }
            Spacer(Modifier.weight(1f))
            IconButton({
                ctx.startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "${a.name}: $repoUrl/releases/latest"),
                    null,
                ))
            }) { Icon(Icons.Default.Share, "Udostępnij") }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text(PUBLISHER, color = c.primary, style = MaterialTheme.typography.titleMedium)
                Text(a.name, fontSize = 36.sp, lineHeight = 42.sp)
            }
            Row(Modifier.padding(20.dp, 16.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(a, 64.dp)
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Stat(rel?.tag ?: "—", "Wersja")
                    Stat(rel?.let { mb(it.size) } ?: "—", "Rozmiar")
                    Stat(rel?.date?.split('-')?.reversed()?.joinToString(".") ?: "—", "Wydano")
                }
            }
            Store.releaseErrors[a.pkg]?.let {
                Text(
                    "Nie udało się pobrać informacji o wydaniu: $it", Modifier.padding(horizontal = 20.dp),
                    color = c.error, style = MaterialTheme.typography.bodySmall,
                )
            }

            Box(Modifier.padding(20.dp, 4.dp)) {
                when (task) {
                    is Task.Downloading -> Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${percent(task.done, task.total)}% z ${mb(task.total)}",
                                Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                            )
                            OutlinedButton({ Store.cancel(ctx, a.pkg) }) { Text("Anuluj") }
                        }
                        LinearProgressIndicator(
                            { task.done.toFloat() / maxOf(1, task.total) }, Modifier.fillMaxWidth().padding(top = 10.dp),
                        )
                    }
                    is Task.Paused -> Column {
                        Text(
                            "Wstrzymano: ${mb(task.done)} z ${mb(task.total)} (${percent(task.done, task.total)}%)",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        LinearProgressIndicator(
                            { task.done.toFloat() / maxOf(1, task.total) }, Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton({ Store.cancel(ctx, a.pkg) }, Modifier.height(48.dp).weight(1f)) { Text("Anuluj") }
                            Button({ resume(ctx, a, task) }, Modifier.height(48.dp).weight(1f)) { Text("Wznów") }
                        }
                    }
                    Task.Verifying -> Column {
                        Text("Sprawdzanie sumy SHA-256…", style = MaterialTheme.typography.bodyLarge)
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp))
                    }
                    Task.Installing -> Column {
                        Text("Instalowanie…", style = MaterialTheme.typography.bodyLarge)
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp))
                    }
                    null -> Actions(a, state, rel)
                }
            }
            installed?.let {
                Text(
                    "Zainstalowana wersja ${it.second}", Modifier.padding(20.dp, 6.dp, 20.dp, 0.dp),
                    style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant,
                )
            }

            if (a.screenshots.isNotEmpty()) {
                LazyRow(contentPadding = PaddingValues(20.dp, 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(a.screenshots.size) { i ->
                        val it = a.screenshots[i]
                        val mod = Modifier.height(280.dp).clip(RoundedCornerShape(20.dp)).background(c.surfaceContainer)
                            .clickable { viewer = i }
                        NetImage(it, 560.dp, mod, ContentScale.FillHeight) { Box(mod.width(158.dp)) }
                    }
                }
            }

            SectionHeader("O tej aplikacji") { aboutOpen = !aboutOpen }
            Text(
                a.summary, Modifier.padding(horizontal = 20.dp),
                fontSize = 22.sp, lineHeight = 30.sp, color = c.onSurfaceVariant,
            )
            if (aboutOpen) Text(
                a.description, Modifier.padding(20.dp, 12.dp, 20.dp, 0.dp),
                style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant,
            )
            if (a.category.isNotEmpty()) Row(Modifier.padding(20.dp, 16.dp, 20.dp, 0.dp)) {
                Text(
                    a.category,
                    Modifier.border(1.dp, c.outlineVariant, RoundedCornerShape(8.dp)).padding(16.dp, 8.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            if (rel != null && rel.notes.isNotBlank()) {
                SectionHeader("Co nowego") { notesOpen = !notesOpen }
                Text(
                    plain(rel.notes), Modifier.padding(horizontal = 20.dp),
                    style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant,
                    maxLines = if (notesOpen) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                )
            }

            val versions = Store.history[a.pkg].orEmpty()
            if (versions.isNotEmpty()) {
                SectionHeader("Historia wersji") { historyOpen = !historyOpen }
                (if (historyOpen) versions else versions.take(3)).forEach { VersionRow(a, it, installed, task == null) }
            }

            val others = listed(ctx).filter { it.pkg != a.pkg }
            if (others.isNotEmpty()) {
                SectionHeader("Inne aplikacje $PUBLISHER")
                others.forEach { AppRow(it, st[it.pkg] ?: State.NotInstalled, onOpen) }
            }

            SectionHeader("Szczegóły")
            Column(
                Modifier.padding(horizontal = 20.dp).fillMaxWidth()
                    .border(1.dp, c.outlineVariant, RoundedCornerShape(12.dp)).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                InfoLine(Icons.Default.Info, "Pakiet", a.pkg)
                InfoLine(Icons.Default.Share, "Źródło", "Wydania z GitHuba: ${a.repo}")
                InfoLine(Icons.Default.Lock, "Instalacja", "Spoza Google Play, przez systemowy instalator Androida")
                Text(
                    "Otwórz repozytorium", Modifier.clickable { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(repoUrl))) },
                    color = c.primary, style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

@Composable
private fun InfoLine(icon: ImageVector, title: String, body: String) {
    Row {
        Icon(icon, null, Modifier.padding(top = 2.dp).size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.padding(start = 20.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun requestInstall(ctx: Context, a: AppEntry, rel: Release) {
    if (!ctx.packageManager.canRequestPackageInstalls()) {
        Store.message.value = "Zezwól LABstore na instalowanie aplikacji i wróć"
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")))
    } else Store.install(ctx, a, rel)
}

private fun resume(ctx: Context, a: AppEntry, t: Task.Paused) {
    Store.resumeTarget(a.pkg, t.url)?.let { requestInstall(ctx, a, it) } ?: run { Store.message.value = "Brak informacji o wydaniu" }
}

@Composable
private fun installAction(a: AppEntry): () -> Unit {
    val ctx = LocalContext.current
    return { Store.releases[a.pkg]?.let { requestInstall(ctx, a, it) } ?: run { Store.message.value = "Brak informacji o wydaniu" } }
}

@Composable
private fun Actions(a: AppEntry, state: State, rel: Release?) {
    val ctx = LocalContext.current
    val install = installAction(a)
    val launch = ctx.packageManager.getLaunchIntentForPackage(a.pkg)
    val big = Modifier.height(48.dp)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        when (state) {
            State.NotInstalled -> Button(install, big.fillMaxWidth(), enabled = rel != null) { Text("Zainstaluj", fontSize = 16.sp) }
            State.Update -> {
                OutlinedButton({ uninstall(ctx, a.pkg) }, big.weight(1f)) { Text("Odinstaluj", fontSize = 16.sp) }
                Button(install, big.weight(1f)) { Text("Aktualizuj", fontSize = 16.sp) }
            }
            State.Installed -> if (a.pkg == ctx.packageName) {
                OutlinedButton({}, big.fillMaxWidth(), enabled = false) { Text("Masz najnowszą wersję", fontSize = 16.sp) }
            } else {
                OutlinedButton({ uninstall(ctx, a.pkg) }, big.weight(1f)) { Text("Odinstaluj", fontSize = 16.sp) }
                Button({ launch?.let { ctx.startActivity(it) } }, big.weight(1f), enabled = launch != null) {
                    Text("Otwórz", fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun VersionRow(a: AppEntry, rel: Release, installed: Pair<Long, String>?, idle: Boolean) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    var askDowngrade by remember { mutableStateOf(false) }
    val latest = Store.releases[a.pkg]
    val current = installed != null && rel.apkUrl == latest?.apkUrl && !Store.isNewer(latest, installed)
    Row(Modifier.fillMaxWidth().padding(20.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(rel.tag, style = MaterialTheme.typography.titleMedium)
                if (rel.prerelease) Pill("beta")
                if (current) Pill("✓ zainstalowana")
            }
            Text(
                "${rel.date.split('-').reversed().joinToString(".")}  •  ${mb(rel.size)}",
                style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant,
            )
        }
        if (idle && !current && a.pkg != ctx.packageName) OutlinedButton({
            if (installed != null && !Store.isNewer(rel, installed)) askDowngrade = true else requestInstall(ctx, a, rel)
        }) { Text("Zainstaluj") }
    }
    if (askDowngrade) AlertDialog(
        onDismissRequest = { askDowngrade = false },
        title = { Text("Starsza wersja") },
        text = {
            Text(
                "Android nie pozwala zainstalować starszej wersji na nowszą. Najpierw odinstaluj obecną " +
                    "(dane aplikacji zostaną usunięte), potem wróć i zainstaluj ${rel.tag}.",
            )
        },
        confirmButton = { Button({ askDowngrade = false; uninstall(ctx, a.pkg) }) { Text("Odinstaluj obecną") } },
        dismissButton = { OutlinedButton({ askDowngrade = false }) { Text("Anuluj") } },
    )
}

/** Zrzuty na pełnym ekranie, przewijane palcem jak w Play. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScreenshotViewer(shots: List<String>, start: Int, onClose: () -> Unit) {
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        val pager = rememberPagerState(start) { shots.size }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(pager, Modifier.fillMaxSize()) { i ->
                Box(Modifier.fillMaxSize().clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                    NetImage(shots[i], 1600.dp, Modifier.fillMaxSize(), ContentScale.Fit) { CircularProgressIndicator() }
                }
            }
            IconButton(onClose, Modifier.align(Alignment.TopStart).padding(8.dp)) {
                Icon(Icons.Default.Clear, "Zamknij", tint = Color.White)
            }
            if (shots.size > 1) Text(
                "${pager.currentPage + 1} / ${shots.size}", Modifier.align(Alignment.BottomCenter).padding(24.dp),
                color = Color.White,
            )
        }
    }
}

private fun uninstall(ctx: Context, pkg: String) =
    ctx.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))

@Composable
private fun SettingSwitch(title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(16.dp, 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(value, onChange)
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Notatki wydań są w Markdownie z GitHuba; tu wystarczy czysty tekst. */
private fun plain(md: String) = md.replace("\r", "")
    .replace(Regex("<[^>]+>"), "")
    .replace(Regex("!?\\[([^]]*)]\\([^)]*\\)"), "$1")
    .replace(Regex("(?m)^#+\\s*"), "")
    .replace(Regex("(?m)^\\s*[-*]\\s+"), "• ")
    .replace("**", "").replace("`", "")
    .replace(Regex("\n{3,}"), "\n\n")
    .trim()
