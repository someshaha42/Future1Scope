package com.example.framescope.encoding

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class NativeAudioEncoder(
    private val muxer: NativeMuxer,
    private val sampleRate: Int = EncodingSpec.AUDIO_SAMPLE_RATE,
    private val onFatalError: (Throwable) -> Unit
) {
    private data class PcmPacket(
        val data: ByteArray,
        val offset: Int,
        val size: Int,
        val presentationTimeUs: Long
    )

    private val running = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private val eosQueued = AtomicBoolean(false)
    private val errorReported = AtomicBoolean(false)
    private val eosLatch = CountDownLatch(1)
    private val queueLock = Any()

    private val availableInputIndices = ArrayDeque<Int>()
    private val pendingPcmPackets = ArrayDeque<PcmPacket>()
    private var lastQueuedPtsUs = 0L

    private var codec: MediaCodec? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    fun start() {
        val encoderThread = HandlerThread("AudioEncoderThread").apply { start() }
        thread = encoderThread
        val encoderHandler = Handler(encoderThread.looper)
        handler = encoderHandler

        val mediaCodec = MediaCodec.createEncoderByType(EncodingSpec.AUDIO_MIME)
        codec = mediaCodec

        mediaCodec.setCallback(
            object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                    if (stopping.get()) {
                        synchronized(queueLock) {
                            if (pendingPcmPackets.isEmpty()) {
                                queueEndOfStream(codec, index)
                                return
                            }
                            availableInputIndices.addLast(index)
                        }
                        drainPendingInputs(codec)
                        return
                    }
                    if (!running.get()) return
                    synchronized(queueLock) {
                        availableInputIndices.addLast(index)
                    }
                    drainPendingInputs(codec)
                }

                override fun onOutputBufferAvailable(
                    codec: MediaCodec,
                    index: Int,
                    info: MediaCodec.BufferInfo
                ) {
                    try {
                        val output = codec.getOutputBuffer(index)
                        if (output != null && info.size > 0) {
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            muxer.writeAudio(output, info)
                        }
                    } catch (t: Throwable) {
                        reportError(t)
                    } finally {
                        runCatching { codec.releaseOutputBuffer(index, false) }
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            eosLatch.countDown()
                        }
                    }
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    if (stopping.get()) return
                    try {
                        muxer.addAudioTrack(format)
                    } catch (t: Throwable) {
                        reportError(t)
                    }
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    eosLatch.countDown()
                    reportError(e)
                }
            },
            encoderHandler
        )

        mediaCodec.configure(
            EncodingSpec.createAudioFormat(sampleRate = sampleRate),
            null,
            null,
            MediaCodec.CONFIGURE_FLAG_ENCODE
        )
        running.set(true)
        mediaCodec.start()
    }

    fun queuePcm(
        pcm: ByteArray,
        size: Int,
        presentationTimeUs: Long
    ) {
        if (!running.get() || stopping.get() || size <= 0) return
        synchronized(queueLock) {
            if (pendingPcmPackets.size >= MAX_PENDING_PACKETS) {
                pendingPcmPackets.removeFirst()
            }
            pendingPcmPackets.addLast(
                PcmPacket(
                    data = pcm,
                    offset = 0,
                    size = size,
                    presentationTimeUs = presentationTimeUs
                )
            )
        }
        handler?.post {
            codec?.let { drainPendingInputs(it) }
        }
    }

    private fun drainPendingInputs(mediaCodec: MediaCodec) {
        while (true) {
            var packet: PcmPacket? = null
            var index: Int? = null
            synchronized(queueLock) {
                if (pendingPcmPackets.isEmpty() || availableInputIndices.isEmpty()) return@synchronized
                packet = pendingPcmPackets.removeFirst()
                index = availableInputIndices.removeFirst()
            }
            val currentPacket = packet ?: break
            val bufferIndex = index ?: break

            val input = runCatching { mediaCodec.getInputBuffer(bufferIndex) }.getOrNull()
            if (input == null) {
                synchronized(queueLock) {
                    pendingPcmPackets.addFirst(currentPacket)
                    availableInputIndices.addFirst(bufferIndex)
                }
                break
            }

            input.clear()
            val capacity = input.remaining()
            if (capacity <= 0) {
                synchronized(queueLock) {
                    pendingPcmPackets.addFirst(currentPacket)
                    availableInputIndices.addFirst(bufferIndex)
                }
                break
            }

            val writeSize = minOf(currentPacket.size, capacity)
            input.put(currentPacket.data, currentPacket.offset, writeSize)

            val pts = currentPacket.presentationTimeUs.coerceAtLeast(lastQueuedPtsUs)
            lastQueuedPtsUs = pts

            runCatching {
                mediaCodec.queueInputBuffer(
                    bufferIndex,
                    0,
                    writeSize,
                    pts,
                    0
                )
            }.onFailure { err ->
                reportError(err)
                return
            }

            if (writeSize < currentPacket.size) {
                val remainingBytes = currentPacket.size - writeSize
                val writtenSamples = writeSize / 2L
                val nextPts = pts + (writtenSamples * 1_000_000L / sampleRate.coerceAtLeast(8_000))
                synchronized(queueLock) {
                    pendingPcmPackets.addFirst(
                        PcmPacket(
                            data = currentPacket.data,
                            offset = currentPacket.offset + writeSize,
                            size = remainingBytes,
                            presentationTimeUs = nextPts
                        )
                    )
                }
            }
        }

        if (!running.get()) {
            val eosIndex = synchronized(queueLock) {
                if (pendingPcmPackets.isEmpty() && availableInputIndices.isNotEmpty()) {
                    availableInputIndices.removeFirst()
                } else {
                    null
                }
            }
            if (eosIndex != null) {
                queueEndOfStream(mediaCodec, eosIndex)
            }
        }
    }

    private fun queueEndOfStream(mediaCodec: MediaCodec, index: Int) {
        if (eosQueued.compareAndSet(false, true)) {
            runCatching {
                mediaCodec.queueInputBuffer(
                    index,
                    0,
                    0,
                    lastQueuedPtsUs,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
            }
        }
    }

    private fun reportError(t: Throwable) {
        if (!stopping.get() && errorReported.compareAndSet(false, true)) {
            onFatalError(t)
        }
    }

    fun stop() {
        if (!stopping.compareAndSet(false, true)) return
        running.set(false)
        val mediaCodec = codec ?: return
        val encoderHandler = handler
        if (encoderHandler != null && thread?.isAlive == true) {
            encoderHandler.post {
                drainPendingInputs(mediaCodec)
            }
            runCatching { eosLatch.await(1_000, TimeUnit.MILLISECONDS) }
            val releaseLatch = CountDownLatch(1)
            val posted = encoderHandler.post {
                runCatching { mediaCodec.stop() }
                runCatching { mediaCodec.release() }
                releaseLatch.countDown()
            }
            if (posted) {
                runCatching { releaseLatch.await(1_000, TimeUnit.MILLISECONDS) }
            } else {
                runCatching { mediaCodec.stop() }
                runCatching { mediaCodec.release() }
            }
        } else {
            runCatching { mediaCodec.stop() }
            runCatching { mediaCodec.release() }
        }
        thread?.quitSafely()
        synchronized(queueLock) {
            availableInputIndices.clear()
            pendingPcmPackets.clear()
        }
        codec = null
        thread = null
        handler = null
    }

    private companion object {
        const val MAX_PENDING_PACKETS = 250
    }
}
