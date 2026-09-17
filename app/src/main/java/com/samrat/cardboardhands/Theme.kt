package com.samrat.cardboardhands

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import zone.ien.hig.CupertinoButton
import zone.ien.hig.CupertinoButtonDefaults
import zone.ien.hig.CupertinoButtonSize
import zone.ien.hig.CupertinoIcon
import zone.ien.hig.CupertinoNavigateBackButton
import zone.ien.hig.CupertinoText
import zone.ien.hig.ExperimentalCupertinoApi
import zone.ien.hig.icons.CupertinoIcons
import zone.ien.hig.icons.outlined.Checkmark
import zone.ien.hig.section.CupertinoSection
import zone.ien.hig.section.SectionScope
import zone.ien.hig.section.SectionItem
import zone.ien.hig.section.SectionLink
import zone.ien.hig.section.sectionTitle
import zone.ien.hig.theme.CupertinoTheme
import zone.ien.hig.theme.darkColorScheme
import zone.ien.hig.theme.lightColorScheme

/** PhoneXR orange, the accent of the launcher icon. */
private val orange = Color(0xFFFF7A1A)
private val orangeDark = Color(0xFFFF9544)

/** Apple HIG look (compose-hig) in light and dark, with the PhoneXR accent. */
@Composable
fun PhoneXRTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme(accent = orangeDark) else lightColorScheme(accent = orange)
    CupertinoTheme(colorScheme = colors, content = content)
}

/** Grouped settings-style page: optional back button, large title, scrolling sections. */
@OptIn(ExperimentalCupertinoApi::class)
@Composable
fun HigPage(
    title: String,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    /** Room under the content, e.g. for the tab bar floating above it. */
    bottomInset: androidx.compose.ui.unit.Dp = 24.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CupertinoTheme.colorScheme.systemGroupedBackground)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(bottom = bottomInset)
    ) {
        if (onBack != null) {
            CupertinoNavigateBackButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp, top = 4.dp)) {
                CupertinoText("Назад")
            }
        } else {
            Spacer(Modifier.height(16.dp))
        }
        CupertinoText(
            title,
            style = CupertinoTheme.typography.largeTitle,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        if (subtitle != null) {
            CupertinoText(
                subtitle,
                style = CupertinoTheme.typography.subhead,
                color = CupertinoTheme.colorScheme.secondaryLabel,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp)
            )
        }
        content()
    }
}

/** Inset grouped section with an optional header and footer text. */
@OptIn(ExperimentalCupertinoApi::class)
@Composable
fun HigSection(
    title: String? = null,
    footer: String? = null,
    content: @Composable SectionScope.() -> Unit
) {
    CupertinoSection(
        title = title?.let { { CupertinoText(it.sectionTitle()) } },
        caption = footer?.let { { CupertinoText(it) } },
        content = content
    )
}

/** Tappable row with a chevron and an optional value on the right. */
@OptIn(ExperimentalCupertinoApi::class)
@Composable
fun SectionScope.HigLink(title: String, value: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    SectionLink(
        onClick = onClick,
        enabled = enabled,
        caption = { if (value != null) CupertinoText(value) },
        title = { CupertinoText(title) }
    )
}

/** Plain row: title with a secondary line under it and anything on the right. */
@OptIn(ExperimentalCupertinoApi::class)
@Composable
fun SectionScope.HigRow(
    title: String,
    detail: String? = null,
    detailColor: Color = Color.Unspecified,
    trailing: @Composable () -> Unit = {}
) {
    SectionItem(trailingContent = trailing) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            CupertinoText(title)
            if (detail != null) {
                CupertinoText(
                    detail,
                    style = CupertinoTheme.typography.footnote,
                    color = if (detailColor == Color.Unspecified) CupertinoTheme.colorScheme.secondaryLabel else detailColor
                )
            }
        }
    }
}

/** Row of a single-choice list: tapping selects it, the chosen one gets a checkmark. */
@OptIn(ExperimentalCupertinoApi::class)
@Composable
fun SectionScope.HigChoice(title: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    SectionLink(
        onClick = onClick,
        chevron = {
            if (selected) {
                CupertinoIcon(CupertinoIcons.Default.Checkmark, null, tint = CupertinoTheme.colorScheme.accent)
            }
        },
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                CupertinoText(title)
                if (detail != null) {
                    CupertinoText(
                        detail,
                        style = CupertinoTheme.typography.footnote,
                        color = CupertinoTheme.colorScheme.secondaryLabel
                    )
                }
            }
        }
    )
}

/** Full-width prominent button placed between sections. */
@OptIn(ExperimentalCupertinoApi::class)
@Composable
fun HigButton(text: String, enabled: Boolean = true, filled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        CupertinoButton(
            onClick = onClick,
            enabled = enabled,
            size = CupertinoButtonSize.Large,
            colors = if (filled) CupertinoButtonDefaults.filledButtonColors() else CupertinoButtonDefaults.tintedButtonColors(),
            modifier = Modifier.fillMaxWidth()
        ) { CupertinoText(text) }
    }
}
