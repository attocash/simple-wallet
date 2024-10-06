package cash.atto.wallet

import cash.atto.commons.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class AccountManager(
    private val network: AttoNetwork,
    private val endpoint: String,
    private val signer: Signer,
    private val authenticator: Authenticator,
    private val representative: AttoPublicKey,
    private val workerClient: WorkerClient,
    private var autoReceive: Boolean,
    transactions: List<AttoTransaction> = arrayListOf()
) : AutoCloseable {
    private val httpClient = HttpClient() {
        install(ContentNegotiation) {
            json()
        }
        install(Logging) {
            level = LogLevel.ALL // Logs everything (headers, bodies, etc.)
            logger = Logger.SIMPLE
        }
        install(HttpTimeout)
    }

    private val scope = CoroutineScope(Dispatchers.Default)
    private val mutex = Mutex()

    val publicKey = signer.publicKey

    private val _accountState = MutableStateFlow<AttoAccount?>(null)
    val accountState = _accountState.asStateFlow()

    private val _receivableFlow = MutableSharedFlow<AttoReceivable>()
    val receivableFlow = _receivableFlow.asSharedFlow()

    private val _receivableState =
        MutableStateFlow<MutableMap<AttoHash, AttoReceivable>>(mutableMapOf())
    val receivableState = _receivableState.asStateFlow()

    private val _transactionFlow = MutableSharedFlow<AttoTransaction>()
    val transactionFlow = _transactionFlow.asSharedFlow()

    private val _transactionState = MutableStateFlow(transactions.toMutableList())
    val transactionState = _transactionState.asStateFlow()

    private val _publishFlow = MutableSharedFlow<AttoTransaction>()


    fun start() {
        require(scope.isActive) {
            "Account manager was cancelled before. Please, recreate it"
        }


        scope.launch {
            _receivableFlow
                .takeWhile { scope.isActive }
                .onEach { receivable ->
                    _receivableState.update {
                        it.apply {
                            put(receivable.hash, receivable)
                        }
                    }
                }
        }

        scope.launch {
            _transactionFlow
                .takeWhile { scope.isActive }
                .onEach { transaction ->
                    _receivableState.update {
                        it.apply {
                            remove(transaction.hash)
                        }
                    }
                    _transactionState.update {
                        it.apply {
                            add(transaction)
                        }
                    }
                }
        }


        run("auto receive") {
            _receivableFlow
                .filter { autoReceive }
                .collect {
                    receive(it)
                }
        }

        run("accounts") {
            val uri = "$endpoint/accounts/$publicKey/stream"
            val jwt = authenticator.getAuthorization()

            httpClient.prepareGet(uri) {
                timeout {
                    socketTimeoutMillis = Long.MAX_VALUE
                }
                headers {
                    append("Authorization", "Bearer $jwt")
                    append("Accept", "application/x-ndjson")
                }
            }
                .execute { response ->
                    val channel: ByteReadChannel = response.body()
                    while (!channel.isClosedForRead && scope.isActive) {
                        val json = channel.readUTF8Line()
                        if (json != null) {
                            val account = Json.decodeFromString<AttoAccount>(json)
                            val height = _accountState.value?.height
                            if (height == null || account.height > height) {
                                println("Received from $uri $json")
                                _accountState.value = account
                            }
                        }
                    }
                }
        }

        run("receivables") {
            val uri = "$endpoint/accounts/$publicKey/receivables/stream"
            val jwt = authenticator.getAuthorization()

            httpClient.prepareGet(uri) {
                timeout {
                    socketTimeoutMillis = Long.MAX_VALUE
                }
                headers {
                    runBlocking {
                        append("Authorization", "Bearer $jwt")
                        append("Accept", "application/x-ndjson")
                    }
                }
            }
                .execute { response ->
                    val channel: ByteReadChannel = response.body()
                    while (!channel.isClosedForRead && scope.isActive) {
                        val json = channel.readUTF8Line()
                        if (json != null) {
                            println("Received from $uri $json")
                            val receivable = Json.decodeFromString<AttoReceivable>(json)
                            _receivableFlow.emit(receivable)
                        }
                    }
                }
        }

        run("transactions") {
            val fromHeight = accountState.value?.height ?: 1U
            val toHeight = ULong.MAX_VALUE
            val uri =
                "$endpoint/accounts/$publicKey/transactions/stream?fromHeight=$fromHeight&toHeight=$toHeight"
            val jwt = authenticator.getAuthorization()

            httpClient.prepareGet(uri) {
                timeout {
                    socketTimeoutMillis = Long.MAX_VALUE
                }
                headers {
                    runBlocking {
                        append("Authorization", "Bearer $jwt")
                        append("Accept", "application/x-ndjson")
                    }
                }
            }
                .execute { response ->
                    val channel: ByteReadChannel = response.body()
                    while (!channel.isClosedForRead && scope.isActive) {
                        val json = channel.readUTF8Line()
                        if (json != null) {
                            println("Received from $uri $json")
                            val transaction = Json.decodeFromString<AttoTransaction>(json)
                            _transactionFlow.emit(transaction)
                        }
                    }
                }
        }
    }

    override fun close() {
        scope.cancel()
    }


    private fun run(name: String, runnable: suspend () -> Any) {
        scope.launch {
            while (scope.isActive) {
                try {
                    runnable.invoke()
                } catch (e: Exception) {
                    println("Failed to stream $name due to ${e.message}. ${e.stackTraceToString()}")
                    delay(10_000)
                }
            }
        }
    }

    suspend fun receive(receivable: AttoReceivable) {
        mutex.withLock {
            val account = accountState.value
            val (block, work) = if (account == null) {
                val block =
                    AttoAccount.open(AttoAlgorithm.V1, representative, receivable, network, getInstant())
                val work = workerClient.work(block)
                block to work
            } else {
                val block = account.receive(receivable, getInstant())
                val work = workerClient.work(block)
                block to work
            }

            val transaction = AttoTransaction(
                block = block,
                signature = signer.sign(block.hash),
                work = work
            )
            publish(transaction)
        }
    }

    suspend fun send(publicKey: AttoPublicKey, amount: AttoAmount) {
        mutex.withLock {
            val account =
                accountState.value ?: throw IllegalStateException("Account doesn't exist yet")

            if (account.balance < amount) {
                throw IllegalStateException("Insufficient balance!")
            }

            val block = account.send(AttoAlgorithm.V1, publicKey, amount, getInstant())
            val transaction = AttoTransaction(
                block = block,
                signature = signer.sign(block.hash),
                work = workerClient.work(block),
            )
            publish(transaction)
        }
    }

    private suspend fun getInstant(): kotlinx.datetime.Instant {
        val diff = httpClient.get("$endpoint/instants/${Clock.System.now()}")
            .body<InstantResponse>()
            .differenceMillis
        return Clock.System.now().plus(diff.milliseconds)
    }

    private suspend fun publish(transaction: AttoTransaction) {
        val uri = "$endpoint/transactions/stream"
        val json = Json.encodeToString(transaction)
        val authorization = authenticator.getAuthorization()

        val response: HttpResponse = httpClient.post(uri) {
            contentType(ContentType.Application.Json)
            setBody(json)
            headers {
                append("Authorization", "Bearer $authorization")
                append("Accept", "application/x-ndjson")
            }
            timeout {
                socketTimeoutMillis = 5.minutes.inWholeMilliseconds
            }
        }

        println("Sent request to $uri $json")

        val channel: ByteReadChannel = response.bodyAsChannel()

        val line = channel.readUTF8Line()

        println("Published: $line")
    }


    @Serializable
    data class InstantResponse(
        val clientInstant: Instant,
        val serverInstant: Instant,
        val differenceMillis: Long,
    )
}
