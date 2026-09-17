package com.videoconverter.android.ui

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
fun LanguageScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val selected = currentAppLanguage()
    val options = languageOptions()
    Column(modifier = Modifier.fillMaxSize()) {
        AppTopBar(
            title = stringResource(minePageTitleRes(MinePage.Language)),
            onBack = onBack,
        )
        OutlinedAppCard(modifier = Modifier.padding(horizontal = 16.dp).padding(top = 8.dp)) {
            options.forEachIndexed { index, language ->
                ListItem(
                    headlineContent = { Text(stringResource(languageLabelRes(language))) },
                    trailingContent = {
                        RadioButton(selected = language == selected, onClick = null)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable {
                            applyAppLanguage(language)
                            (context as? Activity)?.recreate()
                        },
                )
                if (index < options.lastIndex) HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            }
        }
    }
}
