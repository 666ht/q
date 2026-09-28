package io.anonero.ui.home.settings

import AnonNeroTheme
import AnonOutlineButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.anonero.model.WalletManager
import io.anonero.services.WalletState
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResetSyncPage(
    onBackPress: () -> Unit = {}
) {
    val walletState = koinInject<WalletState>()
    val wallet = WalletManager.instance?.wallet
    val defaultHeight = remember(wallet) {
        wallet?.getRestoreHeight() ?: 0L
    }
    var height by remember {
        mutableStateOf("")
    }
    var date by remember {
        mutableStateOf("")
    }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBackPress) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                title = { Text("重置同步区块") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Spacer(modifier = Modifier.height(56.dp))

                OutlinedTextField(
                    value = height,
                    onValueChange = {
                        error = null
                        height = it.filter(Char::isDigit)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    label = { Text("高度恢复") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                if (error != null) {
                    Text(
                        text = error!!,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
            }

            AnonOutlineButton(
                onClick = {
                    val restoreHeight = height.toLongOrNull() ?: defaultHeight

                    if (restoreHeight == null) {
                        error = "请输入有效高度"
                        return@AnonOutlineButton
                    }

                    val result = walletState.resetSyncFromHeight(restoreHeight)
                    if (result.isSuccess) {
                        onBackPress()
                    } else {
                        error = result.exceptionOrNull()?.message ?: "重置失败"
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Text("重置")
            }
        }
    }
}

@Preview(device = "id:pixel_7_pro")
@Composable
private fun ResetSyncPagePreview() {
    AnonNeroTheme {
        ResetSyncPage()
    }
}
