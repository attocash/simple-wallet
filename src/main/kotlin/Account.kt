import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cash.atto.commons.AttoAddress
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoUnit
import cash.atto.wallet.AccountManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun AccountScreen(accountManager: AccountManager) {
    val accountState by accountManager.accountState.collectAsState() // React to state changes

    var recipientAddress by remember { mutableStateOf("") }
    var sendAmount by remember { mutableStateOf("") }
    var transactionStatus by remember { mutableStateOf("") }

    Column(modifier = Modifier.padding(16.dp)) {
        Text("Account Information", style = MaterialTheme.typography.h5)
        Spacer(modifier = Modifier.height(10.dp))

        if (accountState == null) {
            Text("Account not open yet")
            Spacer(modifier = Modifier.height(5.dp))
        }

        // Display account details
        accountState?.let { state ->
            Text("Public Key: ${state.publicKey}")
            Spacer(modifier = Modifier.height(5.dp))

            Text("Network: ${state.network}")
            Spacer(modifier = Modifier.height(5.dp))

            Text("Version: ${state.version}")
            Spacer(modifier = Modifier.height(5.dp))

            Text("Algorithm: ${state.algorithm}")
            Spacer(modifier = Modifier.height(5.dp))

            Text("Height: ${state.height}")
            Spacer(modifier = Modifier.height(5.dp))

            Text("Balance: ${state.balance.toBigDecimal(AttoUnit.ATTO)}")
            Spacer(modifier = Modifier.height(5.dp))

            Text("Last Transaction Hash: ${state.lastTransactionHash}")
            Spacer(modifier = Modifier.height(5.dp))

            Text("Last Transaction Timestamp: ${state.lastTransactionTimestamp}")
            Spacer(modifier = Modifier.height(5.dp))

            Text("Representative Algorithm: ${state.representativeAlgorithm}")
            Spacer(modifier = Modifier.height(5.dp))

            Text("Representative Public Key: ${state.representativePublicKey}")


            Spacer(modifier = Modifier.height(20.dp))

            // Input fields for sending money
            TextField(
                value = recipientAddress,
                onValueChange = { recipientAddress = it },
                label = { Text("Recipient Address") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(10.dp))

            TextField(
                value = sendAmount,
                onValueChange = { sendAmount = it },
                label = { Text("Amount to Send") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions.Default.copy(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
            )
            Spacer(modifier = Modifier.height(10.dp))

            // Button to send money
            Button(onClick = {
                // Convert amount to AttoAmount and publicKey to AttoPublicKey
                val amount = try {
                    AttoAmount.from(AttoUnit.ATTO, sendAmount.toBigDecimal())
                } catch (e: Exception) {
                    transactionStatus = "Invalid amount"
                    null
                }

                val publicKey = try {
                    AttoAddress.parse(recipientAddress).publicKey
                } catch (e: Exception) {
                    transactionStatus = "Invalid public key"
                    null
                }

                if (amount != null && publicKey != null) {
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            accountManager.send(publicKey, amount)
                            transactionStatus = "Transaction successful"
                        } catch (e: Exception) {
                            transactionStatus = "Transaction failed: ${e.message}"
                        }
                    }
                }
            }) {
                Text("Send Money")
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Display transaction status
            if (transactionStatus.isNotEmpty()) {
                Text("Transaction Status: $transactionStatus", color = MaterialTheme.colors.primary)
            }
        }
    }
}
