package com.videoconverter.android.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.videoconverter.android.R
import com.videoconverter.android.ui.theme.LightTokens

@Composable
fun LanguageScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val selected = currentAppLanguage()
    Column(modifier = Modifier.fillMaxSize()) {
        PageHeader(
            title = stringResource(minePageTitleRes(MinePage.Language)),
            leading = {
                Text(
                    stringResource(R.string.action_back),
                    color = Color(LightTokens.Accent),
                    modifier = Modifier.clickable(onClick = onBack),
                )
            },
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 20.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(LightTokens.Card)),
        ) {
            languageOptions().forEachIndexed { index, language ->
                if (index > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color(LightTokens.Border)),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            applyAppLanguage(language)
                            (context as? Activity)?.recreate()
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(languageLabelRes(language)),
                        color = Color(LightTokens.Ink),
                        fontSize = 16.sp,
                    )
                    if (language == selected) {
                        Text("✓", color = Color(LightTokens.Accent), fontSize = 16.sp)
                    }
                }
            }
        }
    }
}
