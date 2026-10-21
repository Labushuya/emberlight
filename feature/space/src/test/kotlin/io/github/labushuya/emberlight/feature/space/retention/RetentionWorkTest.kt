package io.github.labushuya.emberlight.feature.space.retention

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Der periodische Lauf der Aufbewahrung (Paket 3.6c, ADR 0021) mit WorkManager-Testhilfe: Einplanung ohne Doppelte und
 * der Lauf selbst. Die Prüfung wird durch eine Zählung ersetzt, die Logik selbst ist in [RetentionRemindersTest] geprüft.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RetentionWorkTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val originalCheck = RetentionWork.check

    @Before
    fun setUp() {
        val config =
            Configuration
                .Builder()
                .setMinimumLoggingLevel(Log.DEBUG)
                .setExecutor(SynchronousExecutor())
                .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @After
    fun tearDown() {
        RetentionWork.check = originalCheck
    }

    @Test
    fun schedulingTwiceKeepsOneUniqueCheck() {
        RetentionWork.schedule(context)
        RetentionWork.schedule(context)

        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(RetentionWork.UNIQUE_NAME).get()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
    }

    @Test
    fun theWorkerRunsTheCheckOnce() {
        var runs = 0
        RetentionWork.check = { _ -> runs++ }

        val result = TestListenableWorkerBuilder<RetentionWorker>(context).build().startWork().get()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, runs)
    }
}
