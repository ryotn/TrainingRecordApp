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
    private const val DEFAULT_SESSION_DURATION_MINUTES = 60L

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
            HealthConnectClient.Companion.getHealthConnectManageDataIntent(context, context.packageName),
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
                val endTime = if (record.captureTimeMs != null) {
                    Instant.ofEpochMilli(record.captureTimeMs!!)
                } else {
                    Instant.now()
                }

                val zoneId = ZoneId.systemDefault()
                val durationMinutes = if (record.trainingDurationMinutes > 0) {
                    record.trainingDurationMinutes.toLong()
                } else {
                    DEFAULT_SESSION_DURATION_MINUTES
                }
                val startTime = endTime.minusSeconds(durationMinutes * 60L)

                val exerciseSession = ExerciseSessionRecord(
                    startTime = startTime,
                    startZoneOffset = zoneId.rules.getOffset(startTime),
                    endTime = endTime,
                    endZoneOffset = zoneId.rules.getOffset(endTime),
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
        if (record.exerciseName.isNotBlank()) {
            sb.appendLine("エクササイズ: ${record.exerciseName}")
        }
        if (record.machineName.isNotBlank()) {
            sb.appendLine("マシン: ${record.machineName}")
        }
        if (record.trainingDurationMinutes > 0) {
            sb.appendLine("トレーニング時間: ${record.trainingDurationMinutes}分")
        }
        if (record.totalReps > 0) {
            sb.appendLine("総レップ数: ${record.totalReps}回")
        }
        if (record.totalVolumeKg > 0.0) {
            sb.appendLine("総重量: ${record.totalVolumeKg}kg")
        }
        if (record.caloriesKcal > 0.0) {
            sb.appendLine("消費カロリー: ${record.caloriesKcal}kcal")
        }
        record.sets.forEach { set ->
            sb.appendLine("セット ${set.setNumber}: ${set.reps}回 x ${set.weightKg}kg")
        }
        if (record.notes.isNotBlank()) {
            sb.appendLine("備考: ${record.notes}")
        }
        return sb.toString().trim()
    }
}
