package com.android.insta.feature.post.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.android.insta.testutil.FakePostRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PostUploadWorkerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repository = FakePostRepository()

    private fun worker(attempt: Int = 0) = TestListenableWorkerBuilder<PostUploadWorker>(context)
        .setInputData(workDataOf(PostUploadWorker.KEY_DRAFT_ID to "d1"))
        .setRunAttemptCount(attempt)
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                PostUploadWorker(appContext, workerParameters, repository)
        })
        .build()

    @Test
    fun `published draft is success`() = runTest {
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
    }

    @Test
    fun `transient failure retries until the attempt cap, then marks the draft failed`() = runTest {
        repository.publishOutcome = PublishOutcome.Retry
        assertEquals(ListenableWorker.Result.retry(), worker(attempt = 0).doWork())

        assertEquals(ListenableWorker.Result.failure(), worker(attempt = PostUploadWorker.MAX_ATTEMPTS - 1).doWork())
        assertTrue(repository.calls.any { it.startsWith("failed:d1") })
    }

    @Test
    fun `permanent failure marks the draft failed with the server message`() = runTest {
        repository.publishOutcome = PublishOutcome.Failed("Only JPEG, PNG or WebP images are accepted")
        assertEquals(ListenableWorker.Result.failure(), worker().doWork())
        assertTrue("failed:d1:Only JPEG, PNG or WebP images are accepted" in repository.calls)
    }
}
