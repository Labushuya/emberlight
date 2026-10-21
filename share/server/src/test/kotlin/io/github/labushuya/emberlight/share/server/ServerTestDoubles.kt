package io.github.labushuya.emberlight.share.server

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

/** Sperre ohne Android-Laufzeit. Zählt Erwerb und Freigabe. */
internal class FakeServerLock(
    var failOnAcquire: Boolean = false,
) : ServerLock {
    private val acquireCount = AtomicInteger()
    private val releaseCount = AtomicInteger()

    @Volatile
    override var isHeld: Boolean = false

    val acquires: Int get() = acquireCount.get()
    val releases: Int get() = releaseCount.get()

    @Synchronized
    override fun acquire() {
        if (failOnAcquire) throw IllegalStateException("boom")
        acquireCount.incrementAndGet()
        isHeld = true
    }

    @Synchronized
    override fun release() {
        releaseCount.incrementAndGet()
        isHeld = false
    }
}

/**
 * Server ohne Socket. [startFailure] wirft beim Start. [startGate] hält den Start an, bis der Test ihn freigibt.
 */
internal class FakeLocalServer(
    private val startFailure: Throwable? = null,
    private val startGate: CompletableDeferred<Unit>? = null,
) : LocalServer {
    private val mutableState = MutableStateFlow<ServerState>(ServerState.Stopped)
    private val startCount = AtomicInteger()
    private val stopCount = AtomicInteger()

    override val state: StateFlow<ServerState> = mutableState.asStateFlow()

    val starts: Int get() = startCount.get()
    val stops: Int get() = stopCount.get()

    override suspend fun start(config: ServerConfig): Int {
        startCount.incrementAndGet()
        startGate?.await()
        startFailure?.let { throw it }
        mutableState.value = ServerState.Running(FAKE_PORT)
        return FAKE_PORT
    }

    override suspend fun stop() {
        stopCount.incrementAndGet()
        mutableState.value = ServerState.Stopped
    }

    private companion object {
        const val FAKE_PORT = 4711
    }
}

/**
 * Sitzung für Tests mit echter Vault. Die Einladung ist zufällig, der Gastgeber-Token ebenfalls. [credentials] bestimmen,
 * wer sich ausweisen darf.
 */
internal fun testAccess(
    vault: io.github.labushuya.emberlight.share.space.SpaceVault,
    spaceId: io.github.labushuya.emberlight.share.space.SpaceId,
    credentials: List<io.github.labushuya.emberlight.share.space.participants.Credential> = emptyList(),
    name: String = "Test",
    mediaSigner: MediaUrlSigner = MediaUrlSigner.random(),
): SpaceAccess =
    SpaceAccess(
        vault = vault,
        spaceId = spaceId,
        spaceName = name,
        live = LiveAuthorizer(credentials),
        uploads = ActiveUploads(),
        invite =
            io.github.labushuya.emberlight.share.space.participants.Secrets
                .generate(),
        hostToken =
            io.github.labushuya.emberlight.share.space.participants.Secrets
                .generate(),
        mediaSigner = mediaSigner,
    )
