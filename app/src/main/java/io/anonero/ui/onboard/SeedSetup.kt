package io.anonero.ui.onboard

import AnonNeroTheme
import AnonOutlineButton
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.anonero.R


@Composable
fun SeedSetup(
    oNextPressed: () -> Unit = {},
    onBackPressed: () -> Unit = {},
    seed: List<String> = emptyList()
) {
    BackHandler(true) {
        onBackPressed.invoke()
    }

    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.size(24.dp))
            Column(
                modifier = Modifier.weight(.25f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_anon),
                    contentDescription = "Anon nero icon",
                    modifier = Modifier
                        .size(120.dp)
                )
                Spacer(Modifier.height(48.dp))
            }
            LazyVerticalGrid(
                modifier = Modifier
                    .weight(.8f)
                    .padding(horizontal = 16.dp)
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 34.dp),
                columns = GridCells.Fixed(2),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                contentPadding = PaddingValues(
                    horizontal = 24.dp
                )
            ) {
                items(seed.size) { index ->
                    Box(
                        modifier = Modifier
                            .width(60.dp)
                            .padding(start = 24.dp)
                            .align(Alignment.CenterHorizontally)
                    ) {
                        Text(
                            text = "${index + 1}. ${seed[index]}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            textAlign = TextAlign.Start
                        )
                    }

                }
            }

            AnonOutlineButton(
                onClick = {
                    oNextPressed()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = 12.dp
                    ),
                               ) {
                Text(stringResource(R.string.next))
            }
        }
    }
}

@Preview(showBackground = true, device = "id:pixel_8")
@Composable
private fun SetupNodeComposablePreview() {
    AnonNeroTheme {
        SeedSetup(
            seed = "only inquiry tumble vivid useful verify urban black immense essence negative wife deliver organ hungry mirror"
                .split(" ").toList(),
        )
    }
}