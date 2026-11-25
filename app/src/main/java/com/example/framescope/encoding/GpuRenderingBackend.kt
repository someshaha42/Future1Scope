package com.example.framescope.encoding

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.graphics.SurfaceTexture
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class GpuRenderingApi(
    val displayName: String,
    val apiLabel: String,
    val versionLabel: String
) {
    OPENGL_3_2("OpenGL 3.2", "OpenGL", "3.2"),
    VULKAN_1_3("Vulkan 1.3", "Vulkan", "1.3")
}

data class GpuApiSupport(
    val api: GpuRenderingApi,
    val isSupported: Boolean,
    val statusText: String,
    val hardwareDetail: String
)

object GpuCapabilitiesDetector {
    private const val VULKAN_VERSION_1_3 = (1 shl 22) or (3 shl 12)
    private const val GLES_VERSION_3_2 = 0x00030002

    fun detectAll(context: Context): Map<GpuRenderingApi, GpuApiSupport> {
        val opengl = detectOpenGl32(context)
        val vulkan = detectVulkan13(context)
        return mapOf(
            GpuRenderingApi.OPENGL_3_2 to opengl,
            GpuRenderingApi.VULKAN_1_3 to vulkan
        )
    }

    fun selectDefaultApi(supportMap: Map<GpuRenderingApi, GpuApiSupport>): GpuRenderingApi {
        if (supportMap[GpuRenderingApi.VULKAN_1_3]?.isSupported == true) {
            return GpuRenderingApi.VULKAN_1_3
        }
        if (supportMap[GpuRenderingApi.OPENGL_3_2]?.isSupported == true) {
            return GpuRenderingApi.OPENGL_3_2
        }
        return GpuRenderingApi.OPENGL_3_2
    }

    private fun detectOpenGl32(context: Context): GpuApiSupport {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val reqGlEs = am?.deviceConfigurationInfo?.reqGlEsVersion ?: 0
        val probedVersion = probeEglOpenGlVersion()
        val isSupported = reqGlEs >= GLES_VERSION_3_2 || (probedVersion != null && probedVersion >= 3.2f)
        val versionLabel = if (probedVersion != null) {
            String.format(java.util.Locale.US, "OpenGL ES %.1f", probedVersion)
        } else {
            val major = (reqGlEs shr 16) and 0xFFFF
            val minor = reqGlEs and 0xFFFF
            "OpenGL ES $major.$minor"
        }
        return GpuApiSupport(
            api = GpuRenderingApi.OPENGL_3_2,
            isSupported = isSupported,
            statusText = if (isSupported) "✓ Supported" else "Unsupported",
            hardwareDetail = versionLabel
        )
    }

    private fun detectVulkan13(context: Context): GpuApiSupport {
        val pm = context.packageManager
        val hasLevel = pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
        val vulkanFeature = pm.systemAvailableFeatures.firstOrNull {
            it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION
        }
        val rawVersion = vulkanFeature?.version ?: 0
        val isVersion13 = rawVersion >= VULKAN_VERSION_1_3
        val isSupported = hasLevel && isVersion13

        val detail = if (rawVersion > 0) {
            val major = (rawVersion ushr 22) and 0x3FF
            val minor = (rawVersion ushr 12) and 0x3FF
            val patch = rawVersion and 0xFFF
            "Vulkan $major.$minor.$patch"
        } else {
            "Vulkan unavailable"
        }

        return GpuApiSupport(
            api = GpuRenderingApi.VULKAN_1_3,
            isSupported = isSupported,
            statusText = if (isSupported) "✓ Supported" else "Unsupported",
            hardwareDetail = detail
        )
    }

    private fun probeEglOpenGlVersion(): Float? {
        var display = EGL14.EGL_NO_DISPLAY
        var context = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        return try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return null
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null

            val configAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) ||
                numConfigs[0] <= 0 || configs[0] == null
            ) {
                return null
            }

            val ctxAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                EGL14.EGL_NONE
            )
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            if (context == EGL14.EGL_NO_CONTEXT) return null

            val pbufferAttribs = intArrayOf(
                EGL14.EGL_WIDTH, 1,
                EGL14.EGL_HEIGHT, 1,
                EGL14.EGL_NONE
            )
            surface = EGL14.eglCreatePbufferSurface(display, configs[0], pbufferAttribs, 0)
            if (surface == EGL14.EGL_NO_SURFACE) return null

            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return null

            val major = IntArray(1)
            val minor = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_MAJOR_VERSION, major, 0)
            GLES30.glGetIntegerv(GLES30.GL_MINOR_VERSION, minor, 0)
            major[0] + (minor[0] / 10f)
        } catch (_: Throwable) {
            null
        } finally {
            if (display != EGL14.EGL_NO_DISPLAY) {
                runCatching {
                    EGL14.eglMakeCurrent(
                        display,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT
                    )
                }
                if (surface != EGL14.EGL_NO_SURFACE) {
                    runCatching { EGL14.eglDestroySurface(display, surface) }
                }
                if (context != EGL14.EGL_NO_CONTEXT) {
                    runCatching { EGL14.eglDestroyContext(display, context) }
                }
                runCatching { EGL14.eglTerminate(display) }
            }
        }
    }
}

object GpuBackendInitializer {
    fun verifyBackendInitialization(context: Context, api: GpuRenderingApi): Boolean {
        val support = GpuCapabilitiesDetector.detectAll(context)[api] ?: return false
        if (!support.isSupported) return false

        return when (api) {
            GpuRenderingApi.OPENGL_3_2 -> GpuCapabilitiesDetector.detectAll(context)[api]?.isSupported == true
            GpuRenderingApi.VULKAN_1_3 -> {
                runCatching {
                    val renderer = HardwareRenderer()
                    renderer.destroy()
                    true
                }.getOrDefault(false)
            }
        }
    }
}

interface GpuSurfacePipeline {
    val inputSurface: Surface
    fun start()
    fun stop()
}

fun createGpuSurfacePipeline(
    api: GpuRenderingApi,
    width: Int,
    height: Int,
    encoderSurface: Surface,
    onFatalError: (Throwable) -> Unit
): GpuSurfacePipeline {
    return when (api) {
        GpuRenderingApi.OPENGL_3_2 -> OpenGl32SurfacePipeline(
            width = width,
            height = height,
            encoderSurface = encoderSurface,
            onFatalError = onFatalError
        )
        GpuRenderingApi.VULKAN_1_3 -> Vulkan13SurfacePipeline(
            width = width,
            height = height,
            encoderSurface = encoderSurface,
            onFatalError = onFatalError
        )
    }
}

/**
 * Real OpenGL ES 3.2 EGL rendering pipeline:
 * VirtualDisplay -> SurfaceTexture (OES external texture) -> GLSL ES 3.00 shader -> MediaCodec Surface.
 */
class OpenGl32SurfacePipeline(
    private val width: Int,
    private val height: Int,
    private val encoderSurface: Surface,
    private val onFatalError: (Throwable) -> Unit
) : GpuSurfacePipeline {

    private val running = AtomicBoolean(false)
    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var textureId = 0
    private var programId = 0
    private var stMatrixHandle = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var virtualDisplaySurface: Surface? = null

    private val stMatrix = FloatArray(16)

    override val inputSurface: Surface
        get() = virtualDisplaySurface ?: error("OpenGL 3.2 input surface is not initialized.")

    override fun start() {
        val thread = HandlerThread("OpenGL32RenderThread").apply { start() }
        renderThread = thread
        val handler = Handler(thread.looper)
        renderHandler = handler

        val initLatch = CountDownLatch(1)
        var initError: Throwable? = null

        handler.post {
            try {
                initEglAndShaders()
                val tex = SurfaceTexture(textureId).apply {
                    setDefaultBufferSize(width, height)
                    setOnFrameAvailableListener({ st ->
                        if (running.get()) {
                            drawFrame(st)
                        }
                    }, handler)
                }
                surfaceTexture = tex
                virtualDisplaySurface = Surface(tex)
                running.set(true)
            } catch (t: Throwable) {
                initError = t
            } finally {
                initLatch.countDown()
            }
        }

        if (!initLatch.await(2_500, TimeUnit.MILLISECONDS)) {
            error("Timed out initializing OpenGL 3.2 pipeline.")
        }
        initError?.let { throw it }
    }

    private fun initEglAndShaders() {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "Unable to obtain EGL14 display." }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) {
            "Unable to initialize EGL14."
        }
        eglDisplay = display

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        check(
            EGL14.eglChooseConfig(display, attribList, 0, configs, 0, 1, numConfigs, 0) &&
                numConfigs[0] > 0 && configs[0] != null
        ) {
            "Unable to choose OpenGL ES 3.x recordable EGLConfig."
        }

        val ctxAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL14.EGL_NONE
        )
        val context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "Unable to create OpenGL ES 3.x context." }
        eglContext = context

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        val surface = EGL14.eglCreateWindowSurface(display, configs[0], encoderSurface, surfaceAttribs, 0)
        check(surface != EGL14.EGL_NO_SURFACE) { "Unable to create OpenGL window surface." }
        eglSurface = surface

        check(EGL14.eglMakeCurrent(display, surface, surface, context)) {
            "eglMakeCurrent failed for OpenGL 3.2 pipeline."
        }

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )

        programId = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        stMatrixHandle = GLES20.glGetUniformLocation(programId, "uSTMatrix")
        Matrix.setIdentityM(stMatrix, 0)
    }

    private fun drawFrame(st: SurfaceTexture) {
        try {
            st.updateTexImage()
            st.getTransformMatrix(stMatrix)

            GLES20.glViewport(0, 0, width, height)
            GLES20.glUseProgram(programId)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniformMatrix4fv(stMatrixHandle, 1, false, stMatrix, 0)

            val posHandle = GLES20.glGetAttribLocation(programId, "aPosition")
            val texHandle = GLES20.glGetAttribLocation(programId, "aTexCoord")

            QUAD_VERTICES.position(0)
            GLES20.glEnableVertexAttribArray(posHandle)
            GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 16, QUAD_VERTICES)

            QUAD_VERTICES.position(2)
            GLES20.glEnableVertexAttribArray(texHandle)
            GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 16, QUAD_VERTICES)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(posHandle)
            GLES20.glDisableVertexAttribArray(texHandle)

            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, st.timestamp)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        } catch (t: Throwable) {
            if (running.get()) {
                onFatalError(t)
            }
        }
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) {
            renderThread?.quitSafely()
            renderThread = null
            renderHandler = null
            return
        }
        val stopLatch = CountDownLatch(1)
        val handler = renderHandler
        if (handler != null && renderThread?.isAlive == true) {
            val posted = handler.post {
                releaseResources()
                stopLatch.countDown()
            }
            if (posted) {
                runCatching { stopLatch.await(1_000, TimeUnit.MILLISECONDS) }
            } else {
                releaseResources()
            }
        } else {
            releaseResources()
        }
        renderThread?.quitSafely()
        renderThread = null
        renderHandler = null
    }

    private fun releaseResources() {
        runCatching { surfaceTexture?.setOnFrameAvailableListener(null) }
        runCatching { virtualDisplaySurface?.release() }
        virtualDisplaySurface = null
        runCatching { surfaceTexture?.release() }
        surfaceTexture = null

        if (programId != 0) {
            runCatching { GLES20.glDeleteProgram(programId) }
            programId = 0
        }
        if (textureId != 0) {
            runCatching { GLES20.glDeleteTextures(1, intArrayOf(textureId), 0) }
            textureId = 0
        }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            runCatching {
                EGL14.eglMakeCurrent(
                    eglDisplay,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
                )
            }
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                runCatching { EGL14.eglDestroySurface(eglDisplay, eglSurface) }
                eglSurface = EGL14.EGL_NO_SURFACE
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                runCatching { EGL14.eglDestroyContext(eglDisplay, eglContext) }
                eglContext = EGL14.EGL_NO_CONTEXT
            }
            runCatching { EGL14.eglTerminate(eglDisplay) }
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
    }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vShader)
        GLES20.glAttachShader(program, fShader)
        GLES20.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        GLES20.glDeleteShader(vShader)
        GLES20.glDeleteShader(fShader)
        check(linkStatus[0] == GLES20.GL_TRUE) {
            "GL program link failed: ${GLES20.glGetProgramInfoLog(program)}"
        }
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        check(compiled[0] == GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            "Shader compile failed: $log"
        }
        return shader
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142

        val QUAD_VERTICES: FloatBuffer = ByteBuffer.allocateDirect(16 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(
                    floatArrayOf(
                        -1f, -1f, 0f, 0f,
                        1f, -1f, 1f, 0f,
                        -1f, 1f, 0f, 1f,
                        1f, 1f, 1f, 1f
                    )
                )
                position(0)
            }

        const val VERTEX_SHADER = """
            #version 300 es
            in vec4 aPosition;
            in vec4 aTexCoord;
            uniform mat4 uSTMatrix;
            out vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uSTMatrix * aTexCoord).xy;
            }
        """

        const val FRAGMENT_SHADER = """
            #version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision mediump float;
            in vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            out vec4 fragColor;
            void main() {
                fragColor = texture(sTexture, vTexCoord);
            }
        """
    }
}

/**
 * Real Android HardwareRenderer GPU pipeline for Vulkan 1.3 devices:
 * VirtualDisplay -> ImageReader (GPU HardwareBuffer) -> RenderNode + HardwareRenderer -> MediaCodec Surface.
 */
class Vulkan13SurfacePipeline(
    private val width: Int,
    private val height: Int,
    private val encoderSurface: Surface,
    private val onFatalError: (Throwable) -> Unit
) : GpuSurfacePipeline {

    private val running = AtomicBoolean(false)
    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null
    private var imageReader: ImageReader? = null
    private var hardwareRenderer: HardwareRenderer? = null
    private var renderNode: RenderNode? = null
    private val colorSpace = ColorSpace.get(ColorSpace.Named.SRGB)

    override val inputSurface: Surface
        get() = imageReader?.surface ?: error("Vulkan 1.3 input surface is not initialized.")

    override fun start() {
        val thread = HandlerThread("Vulkan13RenderThread").apply { start() }
        renderThread = thread
        val handler = Handler(thread.looper)
        renderHandler = handler

        val initLatch = CountDownLatch(1)
        var initError: Throwable? = null

        handler.post {
            try {
                val node = RenderNode("FrameScopeVulkanFrame").apply {
                    setPosition(0, 0, width, height)
                }
                val renderer = HardwareRenderer().apply {
                    setContentRoot(node)
                    setSurface(encoderSurface)
                    setOpaque(true)
                }
                val reader = ImageReader.Builder(width, height)
                    .setMaxImages(3)
                    .setImageFormat(PixelFormat.RGBA_8888)
                    .setUsage(
                        HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                            HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
                    )
                    .build()

                reader.setOnImageAvailableListener({ r ->
                    if (running.get()) {
                        processLatestImage(r, node, renderer)
                    } else {
                        runCatching { r.acquireLatestImage()?.close() }
                    }
                }, handler)

                renderNode = node
                hardwareRenderer = renderer
                imageReader = reader
                running.set(true)
            } catch (t: Throwable) {
                initError = t
            } finally {
                initLatch.countDown()
            }
        }

        if (!initLatch.await(2_500, TimeUnit.MILLISECONDS)) {
            error("Timed out initializing Vulkan 1.3 pipeline.")
        }
        initError?.let { throw it }
    }

    private fun processLatestImage(
        reader: ImageReader,
        node: RenderNode,
        renderer: HardwareRenderer
    ) {
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        try {
            val hwBuffer = image.hardwareBuffer ?: return
            try {
                val hwBitmap = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace) ?: return
                try {
                    val canvas = node.beginRecording(width, height)
                    canvas.drawBitmap(hwBitmap, 0f, 0f, null)
                    node.endRecording()
                    renderer.createRenderRequest()
                        .setVsyncTime(image.timestamp)
                        .syncAndDraw()
                } finally {
                    runCatching { hwBitmap.recycle() }
                }
            } finally {
                runCatching { hwBuffer.close() }
            }
        } catch (t: Throwable) {
            if (running.get()) {
                onFatalError(t)
            }
        } finally {
            runCatching { image.close() }
        }
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) {
            renderThread?.quitSafely()
            renderThread = null
            renderHandler = null
            return
        }
        val stopLatch = CountDownLatch(1)
        val handler = renderHandler
        if (handler != null && renderThread?.isAlive == true) {
            val posted = handler.post {
                releaseResources()
                stopLatch.countDown()
            }
            if (posted) {
                runCatching { stopLatch.await(1_000, TimeUnit.MILLISECONDS) }
            } else {
                releaseResources()
            }
        } else {
            releaseResources()
        }
        renderThread?.quitSafely()
        renderThread = null
        renderHandler = null
    }

    private fun releaseResources() {
        runCatching { imageReader?.setOnImageAvailableListener(null, null) }
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { hardwareRenderer?.destroy() }
        hardwareRenderer = null
        renderNode = null
    }
}
