package com.example.framescope.encoding

import android.media.MediaCodec
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
    private val onFatalError: (Throwable) -> Unit
) {
    private val stopped = AtomicBoolean(false)
    private val errorReported = AtomicBoolean(false)
    private val eosLatch = CountDownLatch(1)
    private var codec: MediaCodec? = null
    private var encoderThread: HandlerThread? = null
    private var callbackHandler: Handler? = null

    var configuredWidth: Int = width
        private set
    var configuredHeight: Int = height
        private set
    var configuredBitrateBps: Int = existingProjectVideoBitrate
        private set

    lateinit var inputSurface: Surface
        private set

    fun start() {
        val resolved = EncodingSpec.resolveCompatibleVideoConfig(
            tab = tab,
            requestedWidth = width,
            requestedHeight = height,
            requestedBitrateBps = existingProjectVideoBitrate
        )
        configuredWidth = resolved.width
        configuredHeight = resolved.height
        configuredBitrateBps = resolved.bitrateBps

        val thread = HandlerThread("VideoEncoderThread").apply { start() }
        encoderThread = thread
        val handler = Handler(thread.looper)
        callbackHandler = handler

        val mediaCodec = createCodec(resolved.codecName)
        codec = mediaCodec

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

        configureCodecWithFallback(mediaCodec, resolved)
        inputSurface = mediaCodec.createInputSurface()
        mediaCodec.start()
    }

    private fun createCodec(codecName: String?): MediaCodec {
        if (!codecName.isNullOrBlank()) {
            val byName = runCatching { MediaCodec.createByCodecName(codecName) }.getOrNull()
            if (byName != null) return byName
        }
        return MediaCodec.createEncoderByType(tab.mimeType)
    }

    private fun configureCodecWithFallback(
        mediaCodec: MediaCodec,
        resolved: ResolvedVideoConfig
    ) {
        val attempts = listOf(
            Pair(resolved.bitrateMode, true),
            Pair(resolved.bitrateMode, false),
            Pair(null, false)
        )
        var lastError: Throwable? = null
        for ((mode, includeColor) in attempts) {
            val format = MediaFormat.createVideoFormat(tab.mimeType, resolved.width, resolved.height)
            EncodingSpec.configureVideoFormat(
                format = format,
                existingProjectVideoBitrate = resolved.bitrateBps,
                bitrateMode = mode,
                frameRate = resolved.frameRate,
                includeColorMetadata = includeColor
            )
            val result = runCatching {
                mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            if (result.isSuccess) return
            lastError = result.exceptionOrNull()
            runCatching { mediaCodec.reset() }
        }
        throw lastError ?: IllegalStateException("Unable to configure H.264 hardware encoder.")
    }

    private fun validateVideoOutputFormat(format: MediaFormat) {
        if (format.containsKey(MediaFormat.KEY_MIME)) {
            val actualMime = format.getString(MediaFormat.KEY_MIME)
            check(actualMime == null || actualMime.equals(tab.mimeType, ignoreCase = true)) {
                "Encoder output MIME ($actualMime) does not match selected tab (${tab.mimeType})."
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
        val mediaCodec = codec ?: return
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
