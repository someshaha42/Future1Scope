package com.example.framescope.encoding

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class NativeAudioEncoder(
    private val muxer: NativeMuxer,
    private val onFatalError: (Throwable) -> Unit
) {
    private data class PcmPacket(
        val data: ByteArray,
        val size: Int,
        val presentationTimeUs: Long
    )

    private val running = AtomicBoolean(false)
    private val eosQueued = AtomicBoolean(false)
    private val eosLatch = CountDownLatch(1)

    private val availableInputIndices = ConcurrentLinkedQueue<Int>()
    private val pendingPcmPackets = ConcurrentLinkedQueue<PcmPacket>()

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
                    if (!running.get() && pendingPcmPackets.isEmpty()) {
                        queueEndOfStream(codec, index)
                        return
                    }
                    availableInputIndices.offer(index)
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
                        onFatalError(t)
                    } finally {
                        runCatching { codec.releaseOutputBuffer(index, false) }
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            eosLatch.countDown()
                        }
                    }
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    try {
                        muxer.addAudioTrack(format)
                    } catch (t: Throwable) {
                        onFatalError(t)
                    }
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    eosLatch.countDown()
                    onFatalError(e)
                }
            },
            encoderHandler
        )

        mediaCodec.configure(
            EncodingSpec.createAudioFormat(),
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
        if (!running.get() || size <= 0) return
        pendingPcmPackets.offer(PcmPacket(pcm, size, presentationTimeUs))
        handler?.post {
            codec?.let { drainPendingInputs(it) }
        }
    }

    private fun drainPendingInputs(mediaCodec: MediaCodec) {
        while (true) {
            val packet = pendingPcmPackets.peek() ?: break
            val index = availableInputIndices.poll() ?: break
            pendingPcmPackets.poll()

            val input = mediaCodec.getInputBuffer(index)
            if (input == null) {
                availableInputIndices.offer(index)
                break
            }
            val writeSize = minOf(packet.size, input.remaining())
            input.clear()
            input.put(packet.data, 0, writeSize)
            mediaCodec.queueInputBuffer(
                index,
                0,
                writeSize,
                packet.presentationTimeUs,
                0
            )
        }

        if (!running.get() && pendingPcmPackets.isEmpty()) {
            val eosIndex = availableInputIndices.poll()
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
                    0L,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        val mediaCodec = codec ?: return
        handler?.post {
            drainPendingInputs(mediaCodec)
        }
        runCatching { eosLatch.await(1_000, TimeUnit.MILLISECONDS) }
        runCatching { mediaCodec.stop() }
        runCatching { mediaCodec.release() }
        thread?.quitSafely()
        availableInputIndices.clear()
        pendingPcmPackets.clear()
        codec = null
        thread = null
        handler = null
    }
}
