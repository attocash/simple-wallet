package cash.atto.wallet

import cash.atto.commons.AttoBlock
import cash.atto.commons.AttoOpenBlock
import cash.atto.commons.AttoWork
import cash.atto.commons.PreviousSupport
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.minutes


class WorkerClient(
    private val endpoint: String,
    private val authenticator: Authenticator,
) {
    private val httpClient = HttpClient {
        install(ContentNegotiation) {
            json()
        }

        install(Logging) {
            level = LogLevel.ALL // Logs everything (headers, bodies, etc.)
            logger = Logger.SIMPLE
        }
        install(HttpTimeout)
    }

    suspend fun work(block: AttoOpenBlock): AttoWork {
        return this.work(block.timestamp - 1.minutes, block.publicKey.toString())
    }

    suspend fun <T> work(block: T): AttoWork where T : PreviousSupport, T : AttoBlock {
        return this.work(block.timestamp - 1.minutes, block.previous.toString())
    }

    private suspend fun work(timestamp: Instant, target: String): AttoWork {
        val jwt = authenticator.getAuthorization()

        println("Difference ${timestamp - Clock.System.now()}")

        val uri = "$endpoint/works"

        val request = WorkRequest(
            timestamp = timestamp,
            target = target
        )

        return httpClient.post(uri) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(request)
            headers {
                append("Authorization", "Bearer $jwt")
                append("Accept", "application/x-ndjson")
            }
            timeout {
                socketTimeoutMillis = 5.minutes.inWholeMilliseconds
            }
        }
            .body<WorkResponse>()
            .work
    }

    @Serializable
    data class WorkRequest(
        val timestamp: Instant,
        val target: String,
    )

    @Serializable
    data class WorkResponse(
        val work: AttoWork,
    )

}