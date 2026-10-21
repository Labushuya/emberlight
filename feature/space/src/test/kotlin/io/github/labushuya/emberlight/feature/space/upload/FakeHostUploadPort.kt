package io.github.labushuya.emberlight.feature.space.upload

import io.github.labushuya.emberlight.share.space.SpaceId
import kotlinx.coroutines.CompletableDeferred

/**
 * Ersatz für den Upload aus der App. Je Adresse gibt es eine Reihe von Ergebnissen, die der Reihe nach kommen. [gate] hält den
 * Upload an, bis der Test ihn freigibt, damit Fortschritt und Abbruch prüfbar sind. Jeder Aufruf steht in [calls].
 */
internal class FakeHostUploadPort : HostUploadPort {
    var running = true
    var progress: List<UploadProgress> = listOf(UploadProgress(readBytes = 10, totalBytes = 10))
    var gate: CompletableDeferred<Unit>? = null

    /** Aufrufe in Reihenfolge: Adresse und Einwilligung. */
    val calls = mutableListOf<Pair<String, Boolean>>()

    private val names = mutableMapOf<String, PickedFile>()
    private val answers = mutableMapOf<String, ArrayDeque<PickedUploadOutcome>>()

    fun file(
        uri: String,
        name: String,
        size: Long,
    ) {
        names[uri] = PickedFile(name, size)
    }

    fun answer(
        uri: String,
        outcome: PickedUploadOutcome,
    ) {
        answers.getOrPut(uri) { ArrayDeque() }.addLast(outcome)
    }

    override suspend fun canUpload(spaceId: SpaceId): Boolean = running

    override suspend fun describe(uri: String): PickedFile = names[uri] ?: PickedFile(name = uri, sizeBytes = null)

    override suspend fun upload(
        spaceId: SpaceId,
        uri: String,
        keepLocation: Boolean,
        onProgress: (UploadProgress) -> Unit,
    ): PickedUploadOutcome {
        calls += uri to keepLocation
        progress.forEach(onProgress)
        gate?.await()
        return checkNotNull(answers[uri]?.removeFirstOrNull()) { "Keine Antwort für $uri." }
    }
}
