package io.anonero.ui.components

import android.graphics.Typeface
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit

@Composable
fun XmrText(
    text: String = "XMR",
    fontSize: TextUnit = TextUnit.Unspecified,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = LocalContentColor.current,
    style: TextStyle = LocalTextStyle.current,
) {
    val context = LocalContext.current
    val xmrFont = remember {
        FontFamily(
            Typeface.createFromAsset(
                context.assets,
                "160ee2f7b959256f6a2e09db2fa9060b.ttf"
            )
        )
    }

    val annotatedText = buildAnnotatedString {
        var start = 0
        while (start < text.length) {
            val index = text.indexOf("XMR", start)
            if (index < 0) {
                append(text.substring(start))
                break
            }

            append(text.substring(start, index))
            pushStyle(
                SpanStyle(
                    fontFamily = xmrFont,
                    fontSize = fontSize
                )
            )
            append("XMR")
            pop()
            start = index + 3
        }
    }

    Text(
        text = annotatedText,
        modifier = modifier,
        style = style,
        color = color,
    )
}
