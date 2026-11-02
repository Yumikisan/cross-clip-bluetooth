package com.aubynsamuel.clipsync.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.ForkRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aubynsamuel.clipsync.BuildConfig
import com.aubynsamuel.clipsync.R
import com.aubynsamuel.clipsync.core.I18n
import com.aubynsamuel.clipsync.core.SupportReport

@Composable
fun AppInfoCard() {
    val uriHandler = LocalUriHandler.current

    val buyMeACoffee = stringResource(R.string.buy_me_a_coffee)
    val sourceCodeUrl = stringResource(R.string.sourceCode)
    val forkUrl = SupportReport.FORK_URL
    val upstreamLabel = I18n.t("about_upstream")
    val forkLabel = I18n.t("link_fork")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(15.dp)
            .background(
                colorScheme.primaryContainer,
                shape = RoundedCornerShape(30.dp)
            )
            .padding(15.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy((-5).dp)) {
                Text(
                    "Cross Clip",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 18.sp,
                    color = colorScheme.onPrimaryContainer
                )
                Text(
                    BuildConfig.VERSION_NAME,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = colorScheme.onPrimaryContainer
                )
            }
            Row {
                // Two homes, one button each: this build is a fork, and crediting only the
                // upstream (or only the fork) tells half the story.
                IconButton(
                    { uriHandler.openUri(sourceCodeUrl) },
                    colors = IconButtonDefaults.iconButtonColors()
                        .copy(containerColor = colorScheme.onPrimaryContainer)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_github),
                        contentDescription = upstreamLabel,
                        tint = colorScheme.onPrimary
                    )
                }
                Spacer(Modifier.width(4.dp))
                IconButton(
                    { uriHandler.openUri(forkUrl) },
                    colors = IconButtonDefaults.iconButtonColors()
                        .copy(containerColor = colorScheme.onPrimaryContainer)
                ) {
                    Icon(
                        imageVector = Icons.Default.ForkRight,
                        contentDescription = forkLabel,
                        tint = colorScheme.onPrimary
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Says plainly what this build is: a fork that ships both platforms, built on someone
        // else's project. The two buttons below are kept separate for the same reason - it must
        // never look as if the upstream author is being paid for these changes.
        Text(
            I18n.t("about_nature"),
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
        )

        Spacer(Modifier.height(12.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(
                onClick = { uriHandler.openUri(buyMeACoffee) },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
                colors = ButtonDefaults.buttonColors()
                    .copy(
                        containerColor = colorScheme.onPrimaryContainer,
                        contentColor = colorScheme.onPrimary
                    )
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_buymeacoffee),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    I18n.t("sponsor_upstream"),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    maxLines = 1
                )
            }

            Button(
                onClick = { uriHandler.openUri(SupportReport.FORK_SPONSOR_URL) },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
                colors = ButtonDefaults.buttonColors()
                    .copy(
                        containerColor = colorScheme.onPrimaryContainer,
                        contentColor = colorScheme.onPrimary
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    I18n.t("sponsor_fork"),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    maxLines = 1
                )
            }
        }
    }
}