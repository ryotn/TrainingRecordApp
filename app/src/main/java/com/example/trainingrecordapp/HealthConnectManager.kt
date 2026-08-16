package com.example.trainingrecordapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.metadata.Metadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

object HealthConnectManager {
    private const val HEALTH_CONNECT_PACKAGE_NAME = "com.google.android.apps.healthdata"

    val REQUIRED_PERMISSIONS = setOf(
        HealthPermission.getWritePermission(ExerciseSessionRecord::class)
    )

    fun getSdkStatus(context: Context): Int =
        HealthConnectClient.getSdkStatus(context)

    fun openHealthConnectSettings(context: Context) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("market://details?id=$HEALTH_CONNECT_PACKAGE_NAME&url=healthconnect%3A%2F%2Fonboarding")
            setPackage("com.android.vending")
        }
        context.startActivity(intent)
    }

    fun openHealthConnectPermissionSettings(context: Context): Boolean {
        val intents = listOf<Intent>(
            HealthConnectClient.Companion.getHealthConnectManageDataIntent(context, HEALTH_CONNECT_PACKAGE_NAME),
            Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS").apply {
                putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName)
            },
            Intent("android.health.connect.action.MANAGE_HEALTH_PERMISSIONS").apply {
                putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName)
            }
        )

        intents.forEach { intent ->
            if (runCatching {
                    context.startActivity(intent)
                }.isSuccess
            ) {
                return true
            }
        }
        return false
    }

    suspend fun getGrantedPermissions(context: Context): Set<String> =
        withContext(Dispatchers.IO) {
            HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()
        }

    suspend fun hasAllPermissions(context: Context): Boolean {
        val granted = getGrantedPermissions(context)
        return granted.containsAll(REQUIRED_PERMISSIONS)
    }

    suspend fun recordTraining(context: Context, record: TrainingRecord): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val client = HealthConnectClient.getOrCreate(context)
                val now = Instant.now()
                val zoneId = ZoneId.systemDefault()
                val startTime = now.minusSeconds(3600)

                val exerciseSession = ExerciseSessionRecord(
                    startTime = startTime,
                    startZoneOffset = zoneId.rules.getOffset(startTime),
                    endTime = now,
                    endZoneOffset = zoneId.rules.getOffset(now),
                    metadata = Metadata.manualEntry(),
                    exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
                    title = record.exerciseName,
                    notes = buildNotes(record)
                )

                client.insertRecords(listOf(exerciseSession))
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun buildNotes(record: TrainingRecord): String {
        val sb = StringBuilder()
        sb.appendLine("エクササイズ: ${record.exerciseName}")
        record.sets.forEach { set ->
            sb.appendLine("セット ${set.setNumber}: ${set.reps}回 x ${set.weightKg}kg")
        }
        if (record.notes.isNotBlank()) {
            sb.appendLine("備考: ${record.notes}")
        }
        return sb.toString().trim()
    }
}
