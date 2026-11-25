package com.example.framescope.data

data class RecordedVideo(
    val filePath: String,
    val fileName: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val bitrateBps: Long,
    val createdAtMs: Long
)
