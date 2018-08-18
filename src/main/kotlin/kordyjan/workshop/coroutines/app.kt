package kordyjan.workshop.coroutines

import Decrypter
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.ReceiveChannel
import kotlinx.coroutines.experimental.channels.SendChannel
import kotlinx.coroutines.experimental.channels.produce
import kotlinx.coroutines.experimental.selects.select
import kotlin.coroutines.experimental.CoroutineContext

data class Decryption(val decrypter: Decrypter, val input: String, val output: String, val parent: Job)

val executor: CoroutineContext by lazy {
    newFixedThreadPoolContext(nThreads = 7, name = "decrypters_executor")
}

fun main(args: Array<String>) = runBlocking {
    val api = Api(url = "http://localhost:9000", context = DefaultDispatcher)

    val token = api.register(Register("Władimir Iljicz Kotlin")).await().token

    val newPasswords = api.passwords(token)

    val passwordsToRetry = Channel<String>(Decrypter.maxClientCount)

    val passwords = passwordsToRetry merge newPasswords

    var allDecryptions: Job? = null

    lateinit var finishedDecryptions: Channel<Decryption>

    val reset = suspend {
        allDecryptions?.cancel()
        finishedDecryptions = Channel(Decrypter.maxClientCount)
        allDecryptions = Job().also { parent ->
            repeat(Decrypter.maxClientCount) {
                Decrypter().process(passwords.receive(), parent, finishedDecryptions, passwordsToRetry)
            }
        }
    }

    reset()

    while (isActive) {
        for ((decrypter, input, output, parent) in finishedDecryptions) {
            decrypter.process(passwords.receive(), parent, finishedDecryptions, passwordsToRetry)
            api.validate(Validate(token, input, output))
        }
        reset()
    }
}

suspend fun Api.passwords(token: String): ReceiveChannel<String> = produce {
    while (isActive) {
        requestPassword(PasswordRequest(token))
                .await()
                .encryptedPassword
                .also { send(it) }
    }
}

suspend inline fun <T> computation(block: () -> T): T = block().also { yield() }

suspend inline infix fun <T> ReceiveChannel<T>.merge(other: ReceiveChannel<T>): ReceiveChannel<T> = produce {
    while (isActive) {
        select<T> {
            onReceive { it }
            other.onReceive { it }
        }.also { send(it) }
    }
}

suspend fun Decrypter.process(
        password: String,
        parent: Job,
        answerChannel: SendChannel<Decryption>,
        retryChannel: SendChannel<String>
) = launch(executor, parent = parent) {
    try {
        val output = password
                .let { computation { prepare(it) } }
                .let { computation { decode(it) } }
                .let { computation { decrypt(it) } }

        answerChannel.send(Decryption(this@process, password, output, parent))
    } catch (e: Throwable) {
        println("$e\\n")
        if (!answerChannel.isClosedForSend) answerChannel.close()
        retryChannel.send(password)
    }
}