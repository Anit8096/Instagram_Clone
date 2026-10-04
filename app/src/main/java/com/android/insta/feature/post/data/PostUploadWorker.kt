package com.android.insta.feature.post.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/** Publishes one draft. WorkManager handles connectivity waits, process death and backoff. */
class PostUploadWorker(
    context: Context,
    params: WorkerParameters,
    private val repository: PostRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val draftId = inputData.getString(KEY_DRAFT_ID) ?: return Result.failure()
        return when (val outcome = repository.publishDraft(draftId)) {
            PublishOutcome.Published -> Result.success()
            PublishOutcome.Retry ->
                if (runAttemptCount + 1 >= MAX_ATTEMPTS) {
                    repository.markFailed(draftId, "Couldn't reach the server")
                    Result.failure()
                } else {
                    Result.retry()
                }
            is PublishOutcome.Failed -> {
                repository.markFailed(draftId, outcome.message)
                Result.failure()
            }
        }
    }

    companion object {
        const val KEY_DRAFT_ID = "draftId"
        const val TAG = "post-upload"
        const val MAX_ATTEMPTS = 5
    }
}

class WorkManagerUploadScheduler(private val context: Context) : UploadScheduler {

    override fun enqueue(draftId: String) {
        val request = OneTimeWorkRequestBuilder<PostUploadWorker>()
            .setInputData(workDataOf(PostUploadWorker.KEY_DRAFT_ID to draftId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag(PostUploadWorker.TAG)
            .build()
        // KEEP: tapping "retry" while an attempt is still queued must not start a second one.
        WorkManager.getInstance(context).enqueueUniqueWork("post-upload-$draftId", ExistingWorkPolicy.KEEP, request)
    }

    override fun cancelAll() {
        WorkManager.getInstance(context).cancelAllWorkByTag(PostUploadWorker.TAG)
    }
}
