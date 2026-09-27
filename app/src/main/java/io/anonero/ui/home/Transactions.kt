                        modifier = Modifier.size(28.dp),
                        strokeWidth = 2.dp,
                        progress = {
                            ((confirmations.toFloat()) / (10f))
                        }
                    )
                    Text(
                        text = "$confirmations",
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 9.sp
                        )
                    )
                }
        }
        Spacer(modifier = Modifier.size(12.dp))
        Text(
            if (hideAmounts) Formats.maskAmount(amount)
            else Formats.getDisplayAmount(amount),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleLarge
        )
    }
}

@Composable
fun LockButton(onLock: () -> Unit, loading: Boolean = false) {
    val walletState = koinInject<WalletState>()
    val walletLoading by walletState.isLoading.asLiveData().observeAsState(false)
    IconButton(
        modifier = Modifier.alpha(if (walletLoading) 0.2f else 1.0f),
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = Color.White
        ),
        onClick = {
            if (walletLoading || loading) {
                return@IconButton
            }
            onLock()
        }
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Default.Lock, contentDescription = stringResource(R.string.lock))
        }
    }
}