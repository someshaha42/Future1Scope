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
    private val onFatalError: (Throwable) -> Unit
) {
    private val stopped = AtomicBoolean(false)
    private val eosLatch = CountDownLatch(1)
    private var codec: MediaCodec? = null
    private var encoderThread: HandlerThread? = null
    private var callbackHandler: Handler? = null

    lateinit var inputSurface: Surface
        private set

    fun start() {
        val thread = HandlerThread("VideoEncoderThread").apply { start() }
        encoderThread = thread
        val handler = Handler(thread.looper)
        callbackHandler = handler

        val format = MediaFormat.createVideoFormat(tab.mimeType, width, height)
        EncodingSpec.configureVideoFormat(format, existingProjectVideoBitrate)

        val mediaCodec = MediaCodec.createEncoderByType(tab.mimeType)
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
                    try {
                        val output = codec.getOutputBuffer(index)
                        if (output != null && info.size > 0) {
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            muxer.writeVideo(output, info)
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
                        validateVideoOutputFormat(format)
                        muxer.addVideoTrack(format)
                    } catch (t: Throwable) {
                        onFatalError(t)
                    }
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    eosLatch.countDown()
                    onFatalError(e)
                }
            },
            handler
        )

        mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = mediaCodec.createInputSurface()
        mediaCodec.start()
    }

    private fun validateVideoOutputFormat(format: MediaFormat) {
        val actualMime = format.getString(MediaFormat.KEY_MIME)
        check(actualMime == tab.mimeType) {
            "Encoder output MIME ($actualMime) does not match selected tab (${tab.mimeType})."
        }
        if (format.containsKey(MediaFormat.KEY_COLOR_FORMAT)) {
            val actualColor = format.getInteger(MediaFormat.KEY_COLOR_FORMAT)
            check(
                actualColor == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible ||
                    actualColor == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar ||
                    actualColor == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar ||
                    actualColor == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            ) {
                "Encoder output is not the required 8-bit YUV420 format ($actualColor)."
            }
        }
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        val mediaCodec = codec ?: return
        runCatching { mediaCodec.signalEndOfInputStream() }
        runCatching { eosLatch.await(1_500, TimeUnit.MILLISECONDS) }
        runCatching { mediaCodec.stop() }
        runCatching { mediaCodec.release() }
        if (::inputSurface.isInitialized) {
            runCatching { inputSurface.release() }
        }
        encoderThread?.quitSafely()
        codec = null
        encoderThread = null
        callbackHandler = null
    }
}
