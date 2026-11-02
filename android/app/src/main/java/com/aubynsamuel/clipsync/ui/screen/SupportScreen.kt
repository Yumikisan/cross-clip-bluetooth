package com.aubynsamuel.clipsync.ui.screen

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Support
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.aubynsamuel.clipsync.core.I18n
import com.aubynsamuel.clipsync.core.SettingsPreferences
import com.aubynsamuel.clipsync.core.SupportReport
import com.aubynsamuel.clipsync.core.showToast
import com.aubynsamuel.clipsync.core.t
import com.aubynsamuel.clipsync.ui.component.SettingItem
import com.aubynsamuel.clipsync.ui.navigation.safePopBackStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/**
 * Help & feedback: the page a user lands on when something went wrong.
 *
 * Order is deliberate — answered questions first, then the form, then the links. Everything runs
 * locally: the report is copied to the clipboard, the log bundle is a zip the user picks a target
 * for. No content is ever uploaded, and clipboard text never enters the report.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportScreen(
    navController: NavHostController,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var category by remember { mutableStateOf(I18n.t("fb_bug")) }
    var detail by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors()
                    .copy(containerColor = Color.Transparent),
                title = {
                    Text(
                        text = t("support_title"),
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.onPrimaryContainer
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.safePopBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = t("cancel"),
                            tint = colorScheme.onPrimaryContainer
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(bottom = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // ---- before you ask ----
            SectionTitle(t("faq_title"), t("faq_hint"))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    for (i in 1..5) {
                        HelpBlock(
                            icon = if (i == 1) Icons.Default.Support else Icons.Default.ContentCopy,
                            title = t("faq_q$i"),
                            body = t("faq_a$i")
                        )
                    }
                }
            }

            // ---- feedback form ----
            SectionTitle(t("feedback_title"), t("feedback_desc"))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        t("feedback_kind"),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.onSurface
                    )
                    // Category chips: one tap, and the report says which area broke.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("fb_bug", "fb_ui", "fb_sync", "fb_idea").forEach { key ->
                            val label = t(key)
                            AssistChip(
                                onClick = { category = label },
                                label = { Text(label, fontSize = 11.sp) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = if (category == label) {
                                        colorScheme.primaryContainer
                                    } else {
                                        Color.Transparent
                                    }
                                )
                            )
                        }
                    }

                    OutlinedTextField(
                        value = detail,
                        onValueChange = { detail = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        placeholder = { Text(t("feedback_detail_hint"), fontSize = 12.sp) },
                        textStyle = MaterialTheme.typography.bodySmall
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = {
                                SupportReport.copy(context, buildReport(context, category, detail))
                                showToast(I18n.t("feedback_copied"), context)
                            },
                            label = { Text(t("feedback_copy"), fontSize = 12.sp) },
                            leadingIcon = {
                                Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp))
                            }
                        )
                        AssistChip(
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    val zip = SupportReport.exportLogs(context, versionName(context))
                                    launch(Dispatchers.Main) {
                                        if (zip != null) {
                                            SupportReport.share(
                                                context, zip, I18n.t("feedback_share_title")
                                            )
                                        } else if (!SupportReport.hasLogs(context)) {
                                            showToast(I18n.t("feedback_logs_none"), context)
                                        } else {
                                            showToast(I18n.t("feedback_logs_failed"), context)
                                        }
                                    }
                                }
                            },
                            label = { Text(t("feedback_export"), fontSize = 12.sp) },
                            leadingIcon = {
                                Icon(Icons.Default.Share, null, Modifier.size(16.dp))
                            }
                        )
                    }

                    Text(
                        t("feedback_logs_hint"),
                        fontSize = 11.sp,
                        color = colorScheme.onSurfaceVariant
                    )
                }
            }

            // ---- links ----
            SectionTitle(t("link_title"), "")
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = colorScheme.primaryContainer.copy(alpha = 0.5f)
                )
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    SettingItem(
                        title = t("link_github"),
                        subTitle = t("feedback_desc"),
                        icon = Icons.Default.BugReport,
                        pressAction = { SupportReport.open(context, SupportReport.ISSUES_URL) }
                    )
                    SettingItem(
                        title = t("link_dev"),
                        subTitle = SupportReport.UPSTREAM_URL,
                        icon = Icons.Default.Person,
                        pressAction = { SupportReport.open(context, SupportReport.UPSTREAM_URL) }
                    )
                    SettingItem(
                        title = t("link_fork"),
                        subTitle = SupportReport.FORK_URL,
                        icon = Icons.AutoMirrored.Filled.Help,
                        pressAction = { SupportReport.open(context, SupportReport.FORK_URL) }
                    )
                }
            }

            // ---- about ----
            SectionTitle(t("about_title"), "")
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        "CCB ${versionName(context)}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.onSurface
                    )
                    Text(
                        t("about_blurb"),
                        fontSize = 12.sp,
                        color = colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Default.Description, null,
                            Modifier.size(14.dp), tint = colorScheme.onSurfaceVariant
                        )
                        Text(
                            t("feedback_logs_hint"),
                            fontSize = 11.sp,
                            color = colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

/** Version name/code straight from the package manager: no build flags to keep in sync. */
private fun versionName(context: Context): String = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    info.versionName ?: "?"
}.getOrDefault("?")

private fun buildReport(context: Context, category: String, detail: String): String {
    val version = versionName(context)
    val code = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        info.versionCode
    }.getOrDefault(0)
    val mode = runCatching { SettingsPreferences(context).getSecurityMode() }.getOrDefault("auto")
    return SupportReport.environmentInfo(context, version, code, mode, category, detail)
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 8.dp)
    ) {
        Text(
            title,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = colorScheme.onSurface
        )
        if (subtitle.isNotBlank()) {
            Text(
                subtitle,
                fontSize = 11.sp,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
    }
}

/** One troubleshooting entry: icon, cause, and the sentence that actually fixes it. */
@Composable
private fun HelpBlock(icon: ImageVector, title: String, body: String) {
    Row {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = colorScheme.primary
        )
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(
                body,
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
