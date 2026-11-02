package com.aubynsamuel.clipsync.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.aubynsamuel.clipsync.core.Thumbnails
import com.aubynsamuel.clipsync.core.openReceivedFile
import com.aubynsamuel.clipsync.core.openReceivedFolder
import com.aubynsamuel.clipsync.ui.icon.CbbIcons
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items as staggeredItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.aubynsamuel.clipsync.core.ActiveTransfer
import com.aubynsamuel.clipsync.core.BoundMode
import com.aubynsamuel.clipsync.core.EntryFormat
import com.aubynsamuel.clipsync.core.FileKind
import com.aubynsamuel.clipsync.core.HistoryDirection
import com.aubynsamuel.clipsync.core.HistoryFilter
import com.aubynsamuel.clipsync.core.I18n
import com.aubynsamuel.clipsync.core.HistoryEntry
import com.aubynsamuel.clipsync.core.HistoryKind
import com.aubynsamuel.clipsync.core.HistoryStore
import com.aubynsamuel.clipsync.core.ReceivedFileSink
import com.aubynsamuel.clipsync.core.SettingsPreferences
import com.aubynsamuel.clipsync.core.TextFormat
import com.aubynsamuel.clipsync.core.TimeRange
import com.aubynsamuel.clipsync.core.Transfers
import com.aubynsamuel.clipsync.core.showToast
import com.aubynsamuel.clipsync.core.t
import com.aubynsamuel.clipsync.ui.navigation.Screens
import com.aubynsamuel.clipsync.ui.navigation.safePopBackStack
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    navController: NavHostController,
    initialKind: String = "all",
    onSendFile: () -> Unit = {},
    onSendFolder: () -> Unit = {}
) {
    val context = LocalContext.current
    val store = remember { HistoryStore.get(context) }
    val entries by store.entries.collectAsStateWithLifecycle()


    var query by remember { mutableStateOf("") }
    var filter by remember {
        mutableStateOf(
            when (initialKind.lowercase()) {
                "text" -> HistoryKind.TEXT
                "file" -> HistoryKind.FILE
                else -> null
            }
        )
    }
    var showClearDialog by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(HistoryFilter()) }

    val prefs = remember { SettingsPreferences(context) }
    var layout by remember { mutableStateOf(prefs.getHistoryLayout()) }

    // One state per layout: the two list types are not interchangeable, and the "back to top"
    // button has to drive whichever one is on screen.
    val gridState = rememberLazyStaggeredGridState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var lines by remember { mutableStateOf(prefs.getHistoryLines()) }

    // One integer tags every personalisation change, so the style is recomputed exactly when
    // something changed instead of on every recomposition (reading four preferences per frame
    // while scrolling is measurable).
    val appearanceRevision by SettingsPreferences.appearanceRevision.collectAsStateWithLifecycle()
    val style = remember(appearanceRevision) {
        HistoryStyle(
            fontScale = prefs.getHistoryFontScale(),
            accentMode = prefs.getHistoryAccentMode(),
            accentColors = prefs.getHistoryAccentColors(),
            density = prefs.getHistoryDensity(),
            thumbnails = prefs.getThumbnails()
        )
    }

    // Settings screen may change either value while this screen is on the back stack.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        layout = prefs.getHistoryLayout()
        lines = prefs.getHistoryLines()
    }

    val visible = remember(entries, query, filter, advanced) {
        // The "files" tab covers folders too: a received folder is a file-shaped thing and
        // hiding it there would make it look lost.
        entries.filter {
            filter == null || it.kind == filter ||
                (filter == HistoryKind.FILE && it.kind == HistoryKind.FOLDER)
        }
            .filter { it.matches(query) }
            .filter { advanced.matches(it) }
    }

    Scaffold(
        // Same round send button as the device page, in the same corner. The history list is
        // exactly where you notice "I want to push that file over", and a corner button does
        // not move with the toolbar.
        floatingActionButton = {
            // Two buttons, one corner: a file is the common case (the big button), a folder is
            // the exception (a smaller one above it). Android's own file manager cannot share a
            // directory at all, so without this a folder can never leave the phone.
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SmallFloatingActionButton(
                    onClick = onSendFolder,
                    shape = RoundedCornerShape(100.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ) {
                    Icon(
                        CbbIcons.Win11Folder,
                        contentDescription = t("send_folder"),
                        modifier = Modifier.size(20.dp)
                    )
                }
                FloatingActionButton(
                    onClick = onSendFile,
                    shape = RoundedCornerShape(100.dp),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Icon(
                        Icons.Default.UploadFile,
                        contentDescription = t("send_file"),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors()
                    .copy(containerColor = Color.Transparent),
                title = {
                    Text(
                        text = t("history"),
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.safePopBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = t("cancel"),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val next = if (layout == SettingsPreferences.LAYOUT_GRID)
                            SettingsPreferences.LAYOUT_LIST else SettingsPreferences.LAYOUT_GRID
                        prefs.setHistoryLayout(next)
                        layout = next
                    }) {
                        Icon(
                            imageVector = if (layout == SettingsPreferences.LAYOUT_GRID)
                                Icons.Default.ViewList else Icons.Default.GridView,
                            contentDescription = t("history_layout"),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    IconButton(onClick = { showClearDialog = true }, enabled = entries.isNotEmpty()) {
                        Icon(
                            Icons.Default.DeleteSweep,
                            contentDescription = t("clear_history"),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    // Same affordance as the device list: history is where you notice a setting
                    // you want to change, so it should not cost a trip back to the device tab.
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = t("settings"),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .size(24.dp)
                            .clickable { navController.navigate(Screens.SettingsScreen) }
                    )
                }
            )
        }
    ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(horizontal = 12.dp)
            ) {
            // Drawn by hand rather than with OutlinedTextField: the Material field has a 56dp
            // minimum, so forcing it to 44dp squeezed the content area and clipped the bottom of
            // every line (and the caret). A BasicTextField inside a bordered row centres the
            // text properly at any height, and keeps the compact row.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(10.dp)
                    )
                    .padding(horizontal = 10.dp)
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            t("search"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Filter bar plus a permanent entry count on the right. The chips scroll on their
            // own so the count never leaves the screen, however narrow the device is.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState())
                ) {
                    FilterChip(
                        selected = filter == null,
                        onClick = { filter = null },
                        label = { Text(t("filter_all")) })
                    FilterChip(
                        selected = filter == HistoryKind.TEXT,
                        onClick = { filter = HistoryKind.TEXT },
                        label = { Text(t("filter_text")) })
                    FilterChip(
                        selected = filter == HistoryKind.FILE,
                        onClick = { filter = HistoryKind.FILE },
                        label = { Text(t("filter_file")) })
                    FilterChip(
                        selected = advanced.isActive,
                        onClick = { showFilterSheet = true },
                        label = {
                            Text(
                                if (advanced.isActive) "${t("filter_more")} ${advanced.activeCount}"
                                else t("filter_more")
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.FilterList,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                }

                // Sits between the chips and the count, which is dead space on a phone and
                // the place a thumb can reach without covering a card.
                if (visible.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            scope.launch {
                                if (layout == SettingsPreferences.LAYOUT_GRID) {
                                    gridState.animateScrollToItem(0)
                                } else {
                                    listState.animateScrollToItem(0)
                                }
                            }
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.VerticalAlignTop,
                            contentDescription = t("history_back_to_top"),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Text(
                    text = t("history_total", visible.size),
                    fontSize = 11.sp,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = if (visible.isEmpty()) 8.dp else 2.dp)
                )
            }

            // Files only become history entries once the last chunk lands, so live transfers
            // get their own strip at the top: the PC's send progress is visible as it happens.
            val transfers by Transfers.active.collectAsStateWithLifecycle()
            if (transfers.isNotEmpty()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(style.rowGap),
                    modifier = Modifier.padding(bottom = 6.dp)
                ) {
                    transfers.forEach { transfer -> TransferProgressCard(transfer) }
                }
            }

            if (visible.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = if (entries.isEmpty()) t("history_empty") else t("history_no_match"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                val onEntryClick: (HistoryEntry) -> Unit = { entry ->
                    when (entry.kind) {
                        HistoryKind.FILE -> openFile(context, entry)
                        HistoryKind.FOLDER -> openFolder(context, entry)
                        HistoryKind.TEXT -> copyEntry(context, entry)
                    }
                }
                val remove: (HistoryEntry) -> Unit = { store.remove(it.id) }

                if (layout == SettingsPreferences.LAYOUT_GRID) {
                    // Staggered, not a plain grid: a plain grid pads every row to the height of
                    // its tallest card, so a two-line entry next to a six-line one leaves a band
                    // of empty space under it. Each card keeps its own height here and the next
                    // one starts right below it — that is the "closing the gap" the two columns
                    // were supposed to have all along.
                    LazyVerticalStaggeredGrid(
                        columns = StaggeredGridCells.Fixed(2),
                        state = gridState,
                        modifier = Modifier.weight(1f),
                        verticalItemSpacing = style.rowGap,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 76.dp)
                    ) {
                        staggeredItems(visible, key = { it.id }) { entry ->
                            HistoryCard(
                                entry = entry, grid = true, lines = lines, style = style,
                                onClick = { onEntryClick(entry) }, onDelete = { remove(entry) }
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(bottom = 76.dp)
                    ) {
                        items(visible, key = { it.id }) { entry ->
                            HistoryCard(
                                entry = entry, grid = false, lines = lines, style = style,
                                onClick = { onEntryClick(entry) }, onDelete = { remove(entry) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(t("clear_history")) },
            text = { Text(t("clear_history_msg")) },
            confirmButton = {
                TextButton(onClick = {
                    store.clear()
                    showClearDialog = false
                }) { Text(t("clear"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text(t("cancel")) }
            }
        )
    }

    if (showFilterSheet) {
        HistoryFilterSheet(
            initial = advanced,
            // Read when the sheet opens, so a change in settings applies to the next open.
            closeOnReset = prefs.getCloseFilterOnReset(),
            onApply = {
                advanced = it
                showFilterSheet = false
            },
            onReset = { advanced = it },
            onDismiss = { showFilterSheet = false }
        )
    }
}

@Composable
private fun HistoryCard(
    entry: HistoryEntry,
    grid: Boolean,
    lines: Int,
    style: HistoryStyle,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    // Classification is cached per entry: scrolling must not re-run the regexes.
    val format = remember(entry.id) { EntryFormat.detectText(entry.text) }
    val fileKind = remember(entry.id) {
        if (entry.kind == HistoryKind.FOLDER) FileKind.FOLDER
        else EntryFormat.detectFile(entry.fileName)
    }
    val isFile = entry.kind == HistoryKind.FILE
    // Files and folders both carry a type tile and take their colour from FileKind; only text
    // uses the text-format palette.
    val isContainer = entry.kind != HistoryKind.TEXT
    val fallbackAccent = if (isContainer) fileKindColor(fileKind) else textFormatColor(format)
    val accentKey = if (isContainer) EntryFormat.keyFor(fileKind) else EntryFormat.keyFor(format)
    val accent = style.accentFor(accentKey, fallbackAccent)
    val extension = if (isFile) entry.fileName.substringAfterLast('.', "").lowercase() else ""
    // An unknown extension is the one case where "Other" tells the user nothing; the
    // extension itself is the most useful word that fits in that slot — and it replaces the
    // generic page icon on the tile, so both clients show the same thing there.
    val extensionTag = if (isFile && fileKind == FileKind.OTHER && extension.isNotBlank())
        "." + extension.uppercase() else null
    val label = when {
        extensionTag != null -> extensionTag
        isContainer -> t(fileKindLabel(fileKind))
        else -> t(textFormatLabel(format))
    }

    // Only image entries get a preview, and only when the user wants one. The disk cache is
    // consulted first so a cached row never touches the original photo while scrolling.
    val wantsThumb = style.thumbnails && isFile && fileKind == FileKind.IMAGE
    val thumb by produceState<Bitmap?>(null, entry.id, wantsThumb) {
        if (!wantsThumb) return@produceState
        // load() runs on Dispatchers.IO and checks the memory cache first; calling cached()
        // here would read the preview file on the composition thread while scrolling.
        value = Thumbnails.load(context, entry)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column {
            // 4:3 and cropped to fill: every entry in the list has the same preview height
            // whatever the photo's own shape, and the image is scaled uniformly rather than
            // stretched. The metadata stays *below* it — overlaying it would make both the text
            // and the picture harder to read.
            thumb?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(4f / 3f)
                )
            }

            Row(
                modifier = Modifier.height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically
            ) {
            // The identifier bar: one glance tells text formats and file kinds apart.
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(accent)
            )

            // Files get a proper type tile instead of the 13dp glyph that used to sit beside the
            // label: at that size every kind looked like the same grey smudge, and an unknown
            // extension was indistinguishable from a document. Images that already show a
            // preview do not need it — the picture says more than the icon does.
            if (isContainer && thumb == null) {
                val tile = if (grid) 34.dp else 40.dp
                Box(
                    modifier = Modifier
                        .padding(start = 8.dp, end = if (grid) 0.dp else 2.dp)
                        .size(tile)
                        .clip(RoundedCornerShape(if (grid) 9.dp else 11.dp))
                        .background(accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (extensionTag != null) {
                        // A page icon says nothing about ".XYZ"; the suffix does. Sizes are
                        // derived from the tile with dp.toSp(), so the system font scale cannot
                        // push the text out of the box.
                        val density = LocalDensity.current
                        val size = with(density) {
                            (tile * if (extensionTag.length > 4) 0.19f else 0.26f).toSp()
                        }
                        Text(
                            text = extensionTag,
                            color = accent,
                            fontSize = size,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 2.dp)
                        )
                    } else {
                        Icon(
                            imageVector = fileKindIcon(fileKind),
                            contentDescription = null,
                            modifier = Modifier.size(tile * 0.55f),
                            tint = accent
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 9.dp, end = 9.dp, top = style.cardVPadding, bottom = style.cardVPadding)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!isContainer) {
                        Icon(
                            imageVector = Icons.Default.TextFields,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = accent
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        text = label,
                        fontSize = (10f * style.fontScale).sp,
                        fontWeight = FontWeight.Medium,
                        color = accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }

                Text(
                    text = entry.preview,
                    fontSize = ((if (grid) 13f else 14f) * style.fontScale).sp,
                    lineHeight = ((if (grid) 17f else 19f) * style.fontScale).sp,
                    maxLines = lines,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 2.dp)
                )

                Text(
                    text = entryMeta(entry, grid),
                    fontSize = (10f * style.fontScale).sp,
                    lineHeight = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }

            // Outside the text column on purpose: inside the header row the button drifted
            // around with the label width and visually collided with the first content line.
            if (!grid) {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = t("delete"),
                        modifier = Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            }
        }
    }
}

/** Live progress strip for a file that is still in flight, in either direction. */
@Composable
private fun TransferProgressCard(transfer: ActiveTransfer) {
    val percent = (transfer.fraction * 100).toInt()
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                // A folder is one row with one bar: the files inside are progress, not
                // entries, so the arrow would not say what is actually moving.
                imageVector = when {
                    transfer.isFolder -> CbbIcons.Win11Folder
                    transfer.incoming -> Icons.Default.Download
                    else -> Icons.Default.Upload
                },
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = transfer.name,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "$percent%",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }

                LinearProgressIndicator(
                    progress = { transfer.fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .height(4.dp)
                )

                val meta = buildList {
                    if (transfer.isFolder) add("${transfer.doneItems}/${transfer.items}")
                    add(
                        if (transfer.totalBytes > 0)
                            "${ReceivedFileSink.formatSize(transfer.doneBytes)} / ${ReceivedFileSink.formatSize(transfer.totalBytes)}"
                        else ReceivedFileSink.formatSize(transfer.doneBytes)
                    )
                    if (transfer.bytesPerSecond > 0)
                        add("${ReceivedFileSink.formatSize(transfer.bytesPerSecond)}/s")
                    transfer.secondsRemaining?.let { left ->
                        if (left > 0) add(t("remaining_short", formatDuration(left)))
                    }
                    if (transfer.peer.isNotBlank()) add(transfer.peer)
                    add(
                        when {
                            transfer.cancelling -> t("cancelling")
                            transfer.incoming -> t("receiving")
                            else -> t("sending")
                        }
                    )
                }.joinToString("  ·  ")

                Text(
                    text = meta,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }

            // A transfer can be long enough that the user needs a way out: 50 MB over RFCOMM
            // is minutes. Tapping this only sets a flag — the byte loop stops on its own next
            // chunk and closes the row, so nothing here has to cancel a coroutine.
            if (!transfer.cancelling) {
                IconButton(
                    onClick = { Transfers.requestCancel(transfer.id) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = t("cancel"),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** `2:05` / `1:02:05`, for the remaining-time hint on an in-flight transfer. */
private fun formatDuration(seconds: Long): String {
    val total = seconds.coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

/** Time line under the content: full meta in list mode, time + size only inside a card. */
@Composable
private fun entryMeta(entry: HistoryEntry, grid: Boolean): String {
    val pattern = if (grid) "MM-dd HH:mm" else "MM-dd HH:mm:ss"
    val time = SimpleDateFormat(pattern, Locale.getDefault()).format(Date(entry.timestamp))
    val size = if (entry.kind != HistoryKind.TEXT && entry.size > 0)
        ReceivedFileSink.formatSize(entry.size) else null
    val items = if (entry.kind == HistoryKind.FOLDER && entry.itemCount > 0)
        t("folder_received_files", entry.itemCount) else null

    if (grid) return listOfNotNull(time, items, size).joinToString(" · ")

    return buildList {
        add(time)
        if (entry.source.isNotBlank()) add(entry.source)
        if (items != null) add(items)
        if (size != null) add(size)
        add(
            when (entry.direction) {
                HistoryDirection.INCOMING -> t("received")
                HistoryDirection.OUTGOING -> t("sent")
                HistoryDirection.LOCAL -> t("local")
            }
        )
    }.joinToString("  ·  ")
}

/*
 * Identifier-bar palette. Text formats and file kinds are picked from the same wheel, so
 * neighbouring hues have to stay far apart: image used to be pink next to the red package
 * colour, which read as the same thing at a glance. Hue plan:
 *   text   violet / green / blue / orange / gold / grey
 *   files  teal / indigo / magenta / slate / brown / red / grey
 */
/**
 * Everything the personalisation settings contribute to a history entry.
 *
 * Immutable on purpose: it is rebuilt when [SettingsPreferences.appearanceRevision] changes and
 * passed down, so no composable has to read preferences during a scroll.
 */
private data class HistoryStyle(
    val fontScale: Float,
    val accentMode: String,
    val accentColors: Map<String, String>,
    val density: String,
    val thumbnails: Boolean
) {
    /** Vertical gap between entries; the built-in values were 6dp (list) and 8dp (cards). */
    val rowGap: Dp
        get() = when (density) {
            SettingsPreferences.DENSITY_COMPACT -> 4.dp
            SettingsPreferences.DENSITY_COZY -> 11.dp
            else -> 7.dp
        }

    /** Inner top/bottom padding of a card, which is what actually changes the row height. */
    val cardVPadding: Dp
        get() = when (density) {
            SettingsPreferences.DENSITY_COMPACT -> 4.dp
            SettingsPreferences.DENSITY_COZY -> 10.dp
            else -> 7.dp
        }

    fun accentFor(key: String, fallback: Color): Color {
        if (accentMode != SettingsPreferences.ACCENT_CUSTOM) return fallback
        val hex = accentColors[key] ?: return fallback
        val clean = hex.trim().trimStart('#')
        if (clean.length != 6) return fallback
        return runCatching { Color(("FF$clean").toLong(16)) }.getOrDefault(fallback)
    }
}

private fun textFormatColor(format: TextFormat): Color = when (format) {
    TextFormat.MARKDOWN -> Color(0xFF7B61FF)
    TextFormat.CODE -> Color(0xFF00A87E)
    TextFormat.URL -> Color(0xFF1E7BE0)
    TextFormat.NUMBER -> Color(0xFFE07A1F)
    TextFormat.LETTER -> Color(0xFFA8862D)
    TextFormat.PLAIN -> Color(0xFF7A8794)
}

private fun fileKindColor(kind: FileKind): Color = when (kind) {
    FileKind.IMAGE -> Color(0xFF0E9AA7)      // teal  — was pink, too close to package red
    FileKind.VIDEO -> Color(0xFF3F51B5)      // indigo
    FileKind.AUDIO -> Color(0xFFC0269B)      // magenta
    FileKind.DOCUMENT -> Color(0xFF5B7C99)   // slate blue
    FileKind.ARCHIVE -> Color(0xFF8D6E3A)    // brown — was orange, collided with package red
    FileKind.PACKAGE -> Color(0xFFC0392B)    // red
    FileKind.OTHER -> Color(0xFF7A8794)
    FileKind.FOLDER -> Color(0xFF546E7A)     // blue grey — container, not a content type
}

private fun textFormatLabel(format: TextFormat): String = when (format) {
    TextFormat.MARKDOWN -> "fmt_markdown"
    TextFormat.CODE -> "fmt_code"
    TextFormat.URL -> "fmt_url"
    TextFormat.NUMBER -> "fmt_number"
    TextFormat.LETTER -> "fmt_letter"
    TextFormat.PLAIN -> "fmt_plain"
}

/**
 * Large glyph for the type tile. Deliberately one step more specific than the colour: the colour
 * separates text from file at a glance, the glyph separates a video from an archive.
 */
private fun fileKindIcon(kind: FileKind): ImageVector = when (kind) {
    FileKind.IMAGE -> Icons.Default.Image
    FileKind.VIDEO -> Icons.Default.Movie
    FileKind.AUDIO -> Icons.Default.AudioFile
    FileKind.DOCUMENT -> Icons.Default.Description
    FileKind.ARCHIVE -> Icons.Default.FolderZip
    FileKind.PACKAGE -> Icons.Default.InstallMobile
    FileKind.OTHER -> Icons.Default.InsertDriveFile
    FileKind.FOLDER -> CbbIcons.Win11Folder
}

private fun fileKindLabel(kind: FileKind): String = when (kind) {
    FileKind.IMAGE -> "kind_image"
    FileKind.VIDEO -> "kind_video"
    FileKind.AUDIO -> "kind_audio"
    FileKind.DOCUMENT -> "kind_document"
    FileKind.ARCHIVE -> "kind_archive"
    FileKind.PACKAGE -> "kind_package"
    FileKind.OTHER -> "kind_other"
    FileKind.FOLDER -> "kind_folder"
}

/**
 * Hands a received/sent file to the system. The implementation lives in
 * `core/FileOpener.kt` because the "file received" notification opens files too, and both
 * entry points must behave identically.
 */
private fun openFile(context: Context, entry: HistoryEntry) {
    // A file this phone *sent* was never copied anywhere we own, so there is nothing to open.
    // Saying "the file is gone" for a perfectly fine send record reads like a bug.
    if (entry.uri.isBlank() && entry.direction == HistoryDirection.OUTGOING) {
        showToast(I18n.t("file_sent_no_copy"), context)
        return
    }
    openReceivedFile(context, entry.uri, entry.fileName)
}

/** Same deal for folders — the notification and the history row share one implementation. */
private fun openFolder(context: Context, entry: HistoryEntry) {
    if (entry.uri.isBlank() && entry.direction == HistoryDirection.OUTGOING) {
        showToast(I18n.t("file_sent_no_copy"), context)
        return
    }
    openReceivedFolder(context, entry.uri, entry.fileName)
}

private fun copyEntry(context: Context, entry: HistoryEntry) {    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    when {
        entry.kind == HistoryKind.TEXT -> {
            clipboard.setPrimaryClip(ClipData.newPlainText("CBB", entry.text))
            showToast(I18n.t("copied"), context)
        }

        entry.uri.isNotBlank() -> {
            runCatching {
                clipboard.setPrimaryClip(
                    ClipData.newUri(context.contentResolver, entry.fileName, Uri.parse(entry.uri))
                )
            }
        }
    }
}

private fun formatTime(timestamp: Long): String =
    SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

// ------------------------------------------------------------------ filter panel

/**
 * Filter panel.
 *
 * Deliberately a full-screen Dialog instead of a ModalBottomSheet: the sheet's drag handle
 * fought the scroll container (the visible jitter) and its gesture layer swallowed the system
 * back action, so the panel felt broken. A dialog has neither problem, and its body scrolls
 * independently of the window.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryFilterSheet(
    initial: HistoryFilter,
    closeOnReset: Boolean,
    onApply: (HistoryFilter) -> Unit,
    onReset: (HistoryFilter) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var draft by remember { mutableStateOf(initial) }
    val scrimPress = remember { MutableInteractionSource() }
    val cardPress = remember { MutableInteractionSource() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(interactionSource = scrimPress, indication = null, onClick = onDismiss)
            )

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.94f)
                    .clickable(interactionSource = cardPress, indication = null) { },
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 12.dp
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 4.dp, top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            t("filter_title"),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = t("cancel"),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp)
                    ) {

            // ------------------------------------------------ text group
            FilterSectionLabel(t("filter_text_group"))

            FilterChipRow(
                options = TextFormat.values().toList(),
                isSelected = { it in draft.textFormats },
                label = { t(textFormatLabel(it)) },
                onToggle = {
                    draft = draft.copy(
                        textFormats = if (it in draft.textFormats) draft.textFormats - it
                        else draft.textFormats + it
                    )
                }
            )

            RangeRow(
                title = t("filter_length"),
                mode = draft.textLengthMode,
                min = draft.textMinLength,
                max = draft.textMaxLength,
                unit = t("unit_chars"),
                onMode = { draft = draft.copy(textLengthMode = it) },
                onMin = { draft = draft.copy(textMinLength = it) },
                onMax = { draft = draft.copy(textMaxLength = it) }
            )

            OutlinedTextField(
                value = draft.keyword,
                onValueChange = { draft = draft.copy(keyword = it) },
                // No fixed height: OutlinedTextField's own minimum is 56dp, and forcing 46
                // clipped the text against its own baseline (the descenders were cut off).
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                placeholder = { Text(t("filter_keyword"), fontSize = 12.sp) },
                textStyle = MaterialTheme.typography.bodySmall,
                singleLine = true,
                shape = RoundedCornerShape(10.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                Text(t("filter_ignore_case"), fontSize = 12.sp, modifier = Modifier.weight(1f))
                // No size override: squeezing a Switch to 40x24 distorted the thumb and made
                // the row look misaligned against the labels above it.
                Switch(
                    checked = draft.ignoreCase,
                    onCheckedChange = { draft = draft.copy(ignoreCase = it) }
                )
            }

            TimeRow(
                title = t("filter_time"),
                selected = draft.textTime,
                customDay = draft.textCustomDay,
                onSelect = { draft = draft.copy(textTime = it) },
                onPickDay = {
                    showDatePicker(context, draft.textCustomDay) { day ->
                        draft = draft.copy(textTime = TimeRange.CUSTOM, textCustomDay = day)
                    }
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

            // ------------------------------------------------ file group
            FilterSectionLabel(t("filter_file_group"))

            OutlinedTextField(
                value = draft.fileExt,
                onValueChange = { draft = draft.copy(fileExt = it) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(t("filter_ext"), fontSize = 12.sp) },
                supportingText = { Text(t("filter_ext_hint"), fontSize = 9.sp) },
                textStyle = MaterialTheme.typography.bodySmall,
                singleLine = true,
                shape = RoundedCornerShape(10.dp)
            )

            RangeRow(
                title = t("filter_size"),
                mode = draft.fileSizeMode,
                min = draft.fileMinKb.toInt(),
                max = draft.fileMaxKb.toInt(),
                unit = "KB",
                onMode = { draft = draft.copy(fileSizeMode = it) },
                onMin = { draft = draft.copy(fileMinKb = it.toLong()) },
                onMax = { draft = draft.copy(fileMaxKb = it.toLong()) }
            )

            TimeRow(
                title = t("filter_time"),
                selected = draft.fileTime,
                customDay = draft.fileCustomDay,
                onSelect = { draft = draft.copy(fileTime = it) },
                onPickDay = {
                    showDatePicker(context, draft.fileCustomDay) { day ->
                        draft = draft.copy(fileTime = TimeRange.CUSTOM, fileCustomDay = day)
                    }
                }
            )

                    }

                    HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                // Clearing is applied right away either way; only the dismissal is
                                // optional, so "reset then rebuild" is possible without reopening.
                                if (closeOnReset) onApply(HistoryFilter())
                                else { draft = HistoryFilter(); onReset(HistoryFilter()) }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text(t("filter_reset")) }

                        Button(
                            onClick = { onApply(draft.copy(keyword = draft.keyword.trim())) },
                            modifier = Modifier.weight(1f)
                        ) { Text(t("filter_apply")) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterSectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 7.dp, bottom = 2.dp)
    )
}

/**
 * Wrapping chip group. The rows used to scroll horizontally, which silently cut the last chip
 * in half and looked like a layout bug on a phone-width sheet; wrapping keeps every option
 * reachable without a hidden scroll area.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> FilterChipRow(
    options: List<T>,
    isSelected: (T) -> Boolean,
    label: @Composable (T) -> String,
    onToggle: (T) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        options.forEach { option ->
            FilterChip(
                selected = isSelected(option),
                onClick = { onToggle(option) },
                // 28dp instead of the 32dp default: six chips still sit on one line at phone
                // width, and the two filter groups fit on one screen.
                modifier = Modifier.height(28.dp),
                label = { Text(label(option), fontSize = 11.sp) }
            )
        }
    }
}

/** ≤ / ≥ / range selector plus the two numeric bounds. Empty bounds mean "no limit". */
@Composable
private fun RangeRow(
    title: String,
    mode: BoundMode,
    min: Int,
    max: Int,
    unit: String,
    onMode: (BoundMode) -> Unit,
    onMin: (Int) -> Unit,
    onMax: (Int) -> Unit
) {
    Column(modifier = Modifier.padding(top = 4.dp)) {
        // Label beside the chips, not above them: saves one text line per row, and the sheet has
        // to show both filter groups without scrolling.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(52.dp)
            )
            Box(modifier = Modifier.weight(1f)) {
                FilterChipRow(
                    options = listOf(
                        BoundMode.ANY, BoundMode.AT_MOST, BoundMode.AT_LEAST, BoundMode.BETWEEN
                    ),
                    isSelected = { it == mode },
                    label = {
                        when (it) {
                            BoundMode.ANY -> t("bound_any")
                            BoundMode.AT_MOST -> "≤"
                            BoundMode.AT_LEAST -> "≥"
                            BoundMode.BETWEEN -> t("bound_between")
                        }
                    },
                    onToggle = { onMode(it) }
                )
            }
        }

        if (mode != BoundMode.ANY) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                if (mode == BoundMode.AT_LEAST || mode == BoundMode.BETWEEN) {
                    OutlinedTextField(
                        value = if (min == 0) "" else min.toString(),
                        onValueChange = { onMin(it.filter(Char::isDigit).take(7).toIntOrNull() ?: 0) },
                        modifier = Modifier.width(96.dp),
                        textStyle = MaterialTheme.typography.bodySmall,
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp)
                    )
                }
                if (mode == BoundMode.BETWEEN) {
                    Text("–", fontSize = 12.sp)
                }
                if (mode == BoundMode.AT_MOST || mode == BoundMode.BETWEEN) {
                    OutlinedTextField(
                        value = if (max == 0) "" else max.toString(),
                        onValueChange = { onMax(it.filter(Char::isDigit).take(7).toIntOrNull() ?: 0) },
                        modifier = Modifier.width(96.dp),
                        textStyle = MaterialTheme.typography.bodySmall,
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp)
                    )
                }
                Text(unit, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TimeRow(
    title: String,
    selected: TimeRange,
    customDay: Long,
    onSelect: (TimeRange) -> Unit,
    onPickDay: () -> Unit
) {
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(52.dp)
            )
            Box(modifier = Modifier.weight(1f)) {
                FilterChipRow(
                    options = listOf(
                        TimeRange.ANY, TimeRange.LAST_5_MIN, TimeRange.LAST_HOUR,
                        TimeRange.TODAY, TimeRange.YESTERDAY, TimeRange.CUSTOM
                    ),
                    isSelected = { it == selected },
                    label = {
                        when (it) {
                            TimeRange.ANY -> t("time_any")
                            TimeRange.LAST_5_MIN -> t("time_5min")
                            TimeRange.LAST_HOUR -> t("time_hour")
                            TimeRange.TODAY -> t("time_today")
                            TimeRange.YESTERDAY -> t("time_yesterday")
                            TimeRange.CUSTOM -> if (customDay > 0 && selected == TimeRange.CUSTOM)
                                SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(customDay))
                            else t("time_custom")
                        }
                    },
                    onToggle = {
                        if (it == TimeRange.CUSTOM) onPickDay() else onSelect(it)
                    }
                )
            }
        }
    }
}

private fun showDatePicker(context: Context, current: Long, onPicked: (Long) -> Unit) {
    val calendar = Calendar.getInstance().apply {
        timeInMillis = if (current > 0) current else System.currentTimeMillis()
    }
    android.app.DatePickerDialog(
        context,
        { _, year, month, day ->
            val picked = Calendar.getInstance().apply {
                set(year, month, day, 0, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }
            onPicked(picked.timeInMillis)
        },
        calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH),
        calendar.get(Calendar.DAY_OF_MONTH)
    ).show()
}
