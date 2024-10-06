import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import cash.atto.commons.*
import cash.atto.wallet.AccountManager
import cash.atto.wallet.Authenticator
import cash.atto.wallet.Signer
import cash.atto.wallet.WorkerClient

@Composable
@Preview
fun SeedScreen() {
    var mnemonicText by remember { mutableStateOf("") }
    var isMnemonicVisible by remember { mutableStateOf(false) }
    var mnemonic: AttoMnemonic? by remember { mutableStateOf(null) }

    MaterialTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            if (mnemonic == null) {
                // Text Field for entering or generating a mnemonic
                TextField(
                    value = mnemonicText,
                    onValueChange = { mnemonicText = it },
                    placeholder = { Text("Enter or generate mnemonic") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Text)
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Set and Generate buttons
                Row {
                    Button(onClick = {
                        if (mnemonicText.isNotEmpty()) {
                            // Attempt to set the mnemonic
                            mnemonic = try {
                                AttoMnemonic(mnemonicText)
                            } catch (e: Exception) {
                                null // Handle invalid mnemonic case
                            }
                        }
                    }) {
                        Text("Set Mnemonic")
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Button(onClick = {
                        mnemonicText = AttoMnemonic.generate().words.joinToString(" ") ?: ""
                    }) {
                        Text("Generate Mnemonic")
                    }
                }
            } else {
                // Display mnemonic with show/hide functionality
                Row {
                    SelectionContainer {
                        Text(
                            text = if (isMnemonicVisible) mnemonicText else "●".repeat(mnemonicText.length),
                            style = MaterialTheme.typography.h6
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    IconButton(onClick = { isMnemonicVisible = !isMnemonicVisible }) {
                        Icon(
                            imageVector = if (isMnemonicVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "Toggle Mnemonic Visibility"
                        )
                    }

                }

                Row {
                    mnemonic?.let {
                        SelectionContainer {
                            Text(
                                text = it.toSeed().toPrivateKey(0U).toPublicKey().toAddress(AttoAlgorithm.V1).value,
                                style = MaterialTheme.typography.h6
                            )
                        }
                    }
                }

                Row {
                    val walletGatekeeperUrl = "https://wallet-gatekeeper.dev.application.atto.cash"
                    val gatekeeperUrl = "https://gatekeeper.dev.application.atto.cash"

//                    val gatekeeperUrl = "http://localhost:8080"

                    val privateKey = mnemonic!!.toSeed().toPrivateKey(0U)
                    val signer = Signer(privateKey)
                    val authenticator =
                        Authenticator(walletGatekeeperUrl, signer)
                    val workerClient = WorkerClient(gatekeeperUrl, authenticator)

                    val accountManager = AccountManager(
                        network = AttoNetwork.DEV,
                        endpoint = gatekeeperUrl,
                        signer = signer,
                        authenticator = authenticator,
                        representative = privateKey.toPublicKey(),
                        workerClient = workerClient,
                        autoReceive = true,
                    )
                    accountManager.start()
                    AccountScreen(accountManager)
                }
            }
        }
    }
}

fun main() = application {
    Window(onCloseRequest = ::exitApplication) {
        SeedScreen()
    }
}
