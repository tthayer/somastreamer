package com.thelightphone.somafm

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/** Themed full-screen column with the standard top bar; [onBack] null hides the back button. */
@Composable
internal fun SomaScaffold(
    title: String,
    onBack: (() -> Unit)?,
    rightButton: LightBarButton? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val themeColors by LightThemeController.colors.collectAsState()
    LightTheme(colors = themeColors) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background),
        ) {
            LightTopBar(
                leftButton = onBack?.let { LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = it) },
                center = LightTopBarCenter.Text(title),
                rightButton = rightButton,
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            content()
        }
    }
}

internal fun refreshButton(onClick: () -> Unit) = LightBarButton.LightIcon(icon = LightIcons.REFRESH, onClick = onClick)

@Composable
internal fun MessageText(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Copy,
        lighten = true,
        modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
    )
}

/** Renders a [LoadState]: loading and failure messages, or [ready] content in a scroll view. */
@Composable
internal fun <T> LoadStateContent(
    state: LoadState<T>,
    emptyMessage: String,
    isEmpty: (T) -> Boolean,
    ready: @Composable ColumnScope.(T) -> Unit,
) {
    when (state) {
        is LoadState.Loading -> MessageText("Loading…")
        is LoadState.Failed -> MessageText(state.message)
        is LoadState.Ready -> if (isEmpty(state.value)) {
            MessageText(emptyMessage)
        } else {
            LightScrollView(modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp())) {
                ready(state.value)
            }
        }
    }
}

/** A tappable row: optional fixed-width [marker] column, a title, and an optional detail line. */
@Composable
internal fun SomaRow(
    title: String,
    detail: String? = null,
    marker: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.lightClickable(onClick = onClick) else it }
            .padding(vertical = 0.6f.gridUnitsAsDp()),
    ) {
        if (marker != null) {
            LightText(
                text = marker,
                variant = LightTextVariant.Detail,
                modifier = Modifier.width(3f.gridUnitsAsDp()),
            )
        }
        Column {
            LightText(text = title, variant = LightTextVariant.Copy)
            if (detail != null) {
                LightText(
                    text = detail,
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(top = 0.25f.gridUnitsAsDp()),
                )
            }
        }
    }
}

@Composable
internal fun SectionHeader(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Subheading,
        modifier = Modifier.padding(top = 0.75f.gridUnitsAsDp(), bottom = 0.25f.gridUnitsAsDp()),
    )
}
