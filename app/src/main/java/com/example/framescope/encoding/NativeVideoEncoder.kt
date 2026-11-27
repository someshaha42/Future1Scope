package com.example.framescope.encoding

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class NativeVideoEncoder(
    private val tab: EncodingTab,
    private val width: Int,
    private val height: Int,
    private val existingProjectVideoBitrate: Int,
    private val muxer: NativeMuxer,
    private val preferredFrameRate: Int = 60,
    private val onFatalError: (Throwable) -> Unit
) {
    private val stopped = AtomicBoolean(false)
    private val errorReported = AtomicBoolean(false)
    private val eosLatch = CountDownLatch(1)
    private var codec: MediaCodec? = null
    private var encoderThread: HandlerThread? = null
    private var callbackHandler: Handler? = null

    val activeTab: EncodingTab = tab
    private val activeMime: String = tab.mimeType
    val configuredWidth: Int = (width and 1.inv()).coerceAtLeast(16)
    val configuredHeight: Int = (height and 1.inv()).coerceAtLeast(16)
    val configuredBitrateBps: Int = existingProjectVideoBitrate

    lateinit var inputSurface: Surface
        private set

    fun start() {
        val thread = HandlerThread("VideoEncoderThread").apply { start() }
        encoderThread = thread
        val handler = Handler(thread.looper)
        callbackHandler = handler

        var mediaCodec: MediaCodec? = null
        try {
            mediaCodec = MediaCodec.createEncoderByType(activeMime)
            attachCallback(mediaCodec, handler)
            configureNativeCodec(mediaCodec)
            val surface = mediaCodec.createInputSurface()
            inputSurface = surface
            codec = mediaCodec
            mediaCodec.start()
        } catch (t: Throwable) {
            runCatching { mediaCodec?.stop() }
            runCatching { mediaCodec?.release() }
            encoderThread?.quitSafely()
            encoderThread = null
            callbackHandler = null
            throw t
        }
    }

    private fun attachCallback(mediaCodec: MediaCodec, handler: Handler) {
        mediaCodec.setCallback(
            object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                    // Surface-input encoder: no ByteBuffer input is queued.
                }

                override fun onOutputBufferAvailable(
                    codec: MediaCodec,
                    index: Int,
                    info: MediaCodec.BufferInfo
                ) {
                    if (stopped.get()) {
                        runCatching { codec.releaseOutputBuffer(index, false) }
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            eosLatch.countDown()
                        }
                        return
                    }
                    try {
                        val output = codec.getOutputBuffer(index)
                        if (output != null && info.size > 0) {
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            muxer.writeVideo(output, info)
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
                    if (stopped.get()) return
                    try {
                        validateVideoOutputFormat(format)
                        muxer.addVideoTrack(format)
                    } catch (t: Throwable) {
                        reportError(t)
                    }
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    eosLatch.countDown()
                    reportError(e)
                }
            },
            handler
        )
    }

    private fun configureNativeCodec(mediaCodec: MediaCodec) {
        val attempts = listOf(
            Pair(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR as Int?, true),
            Pair(null, false)
        )
        var lastError: Throwable? = null
        for ((bitrateMode, includeColorMetadata) in attempts) {
            val format = MediaFormat.createVideoFormat(
                activeMime,
                configuredWidth,
                configuredHeight
            )
            EncodingSpec.configureVideoFormat(
                format = format,
                existingProjectVideoBitrate = configuredBitrateBps,
                bitrateMode = bitrateMode,
                frameRate = preferredFrameRate,
                includeColorMetadata = includeColorMetadata
            )
            val result = runCatching {
                mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            if (result.isSuccess) return
            lastError = result.exceptionOrNull()
            runCatching { mediaCodec.reset() }
        }
        throw lastError ?: IllegalStateException("Unable to configure ${tab.displayName} encoder.")
    }

    private fun validateVideoOutputFormat(format: MediaFormat) {
        if (format.containsKey(MediaFormat.KEY_MIME)) {
            val actualMime = format.getString(MediaFormat.KEY_MIME)
            check(actualMime == null || actualMime.equals(activeMime, ignoreCase = true)) {
                "Encoder output MIME ($actualMime) does not match active codec ($activeMime)."
            }
        }
    }

    private fun reportError(t: Throwable) {
        if (!stopped.get() && errorReported.compareAndSet(false, true)) {
            onFatalError(t)
        }
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        val mediaCodec = codec ?: run {
            encoderThread?.quitSafely()
            encoderThread = null
            callbackHandler = null
            return
        }
        runCatching { mediaCodec.signalEndOfInputStream() }
        runCatching { eosLatch.await(1_500, TimeUnit.MILLISECONDS) }

        val handler = callbackHandler
        if (handler != null && encoderThread?.isAlive == true) {
            val releaseLatch = CountDownLatch(1)
            val posted = handler.post {
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

        if (::inputSurface.isInitialized) {
            runCatching { inputSurface.release() }
        }
        encoderThread?.quitSafely()
        codec = null
        encoderThread = null
        callbackHandler = null
    }
}
