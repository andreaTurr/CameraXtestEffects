/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.mylibrarycamera

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import androidx.camera.core.DynamicRange
import com.example.mylibrarycamera.gles.ShaderProvider
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale
import java.util.Objects
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OpenGLRenderer renders texture image to the output surface.
 *
 *
 * OpenGLRenderer's methods must run on the same thread, so called GL thread. The GL thread is
 * locked as the thread running the [.init] method, otherwise an
 * [IllegalStateException] will be thrown when other methods are called.
 */
@WorkerThread
@RequiresApi(21) // TODO(b/200306659): Remove and replace with annotation on package-info.java

class OpenGlRenderer {
    private val mInitialized = AtomicBoolean(false)

    @VisibleForTesting
    val mOutputSurfaceMap: MutableMap<Surface, OutputSurface> = HashMap()
    private var mGlThread: Thread? = null
    private var mEglDisplay = EGL14.EGL_NO_DISPLAY
    private var mEglContext = EGL14.EGL_NO_CONTEXT
    private var mEglConfig: EGLConfig? = null
    private var mTempSurface = EGL14.EGL_NO_SURFACE
    private var mCurrentSurface: Surface? = null
    private var mExternalTextureId = -1
    private var mProgramHandle = -1
    private var mTexMatrixLoc = -1
    private var mPositionLoc = -1
    private var mTexCoordLoc = -1

    /**
     * Initializes the OpenGLRenderer
     *
     *
     * Initialization must be done before calling other methods, otherwise an
     * [IllegalStateException] will be thrown. Following methods must run on the same
     * thread as this method, so called GL thread, otherwise an [IllegalStateException]
     * will be thrown.
     *
     * @throws IllegalStateException    if the renderer is already initialized or failed to be
     * initialized.
     * @throws IllegalArgumentException if the ShaderProvider fails to create shader or provides
     * invalid shader string.
     */
    fun init(dynamicRange: DynamicRange, shaderProvider: ShaderProvider) {
        var dynamicRange = dynamicRange
        checkInitializedOrThrow(false)
        try {
            if (is10BitHdr(dynamicRange)) {
                val glExtensions = getGlExtensionsBeforeInitialized(dynamicRange)
                if (!glExtensions.contains("GL_EXT_YUV_target")) {
                    Log.w(TAG, "Device does not support GL_EXT_YUV_target. Fallback to SDR.")
                    dynamicRange = DynamicRange.SDR
                }
            }
            createEglContext(dynamicRange)
            createTempSurface()
            makeCurrent(mTempSurface)
            createProgram(dynamicRange, shaderProvider)
            loadLocations()
            createTexture()
            useAndConfigureProgram()
        } catch (e: IllegalStateException) {
            releaseInternal()
            throw e
        } catch (e: IllegalArgumentException) {
            releaseInternal()
            throw e
        }
        mGlThread = Thread.currentThread()
        mInitialized.set(true)
    }

    fun is10BitHdr(dynamicRange: DynamicRange): Boolean {
        return isFullySpecified(dynamicRange) && dynamicRange.encoding != DynamicRange.ENCODING_SDR && dynamicRange.bitDepth == DynamicRange.BIT_DEPTH_10_BIT
    }

    fun isFullySpecified(dynamicRange: DynamicRange): Boolean {
        return dynamicRange.encoding != DynamicRange.ENCODING_UNSPECIFIED && dynamicRange.encoding != DynamicRange.ENCODING_HDR_UNSPECIFIED && dynamicRange.bitDepth != DynamicRange.BIT_DEPTH_UNSPECIFIED
    }

    /**
     * Releases the OpenGLRenderer
     *
     * @throws IllegalStateException if the caller doesn't run on the GL thread.
     */
    fun release() {
        if (!mInitialized.getAndSet(false)) {
            return
        }
        checkGlThreadOrThrow()
        releaseInternal()
    }

    /**
     * Register the output surface.
     *
     * @throws IllegalStateException if the renderer is not initialized or the caller doesn't run
     * on the GL thread.
     */
    fun registerOutputSurface(surface: Surface) {
        checkInitializedOrThrow(true)
        checkGlThreadOrThrow()
        if (!mOutputSurfaceMap.containsKey(surface)) {
            mOutputSurfaceMap[surface] =
                NO_OUTPUT_SURFACE
        }
    }

    /**
     * Unregister the output surface.
     *
     * @throws IllegalStateException if the renderer is not initialized or the caller doesn't run
     * on the GL thread.
     */
    fun unregisterOutputSurface(surface: Surface) {
        checkInitializedOrThrow(true)
        checkGlThreadOrThrow()
        removeOutputSurfaceInternal(surface, true)
    }

    val textureName: Int
        /**
         * Gets the texture name.
         *
         * @return the texture name
         * @throws IllegalStateException if the renderer is not initialized or the caller doesn't run
         * on the GL thread.
         */
        get() {
            checkInitializedOrThrow(true)
            checkGlThreadOrThrow()
            return mExternalTextureId
        }

    /**
     * Renders the texture image to the output surface.
     *
     * @throws IllegalStateException if the renderer is not initialized, the caller doesn't run
     * on the GL thread or the surface is not registered by
     * [.registerOutputSurface].
     */
    fun render(
        timestampNs: Long, textureTransform: FloatArray,
        surface: Surface
    ) {
        checkInitializedOrThrow(true)
        checkGlThreadOrThrow()
        var outputSurface: OutputSurface? = getOutSurfaceOrThrow(surface)

        // Workaround situations that out surface is failed to create or needs to be recreated.
        if (outputSurface === NO_OUTPUT_SURFACE) {
            outputSurface = createOutputSurfaceInternal(surface)
            if (outputSurface == null) {
                return
            }
            mOutputSurfaceMap[surface] = outputSurface
        }

        // Set output surface.
        if (surface !== mCurrentSurface) {
            makeCurrent(outputSurface!!.eglSurface)
            mCurrentSurface = surface
            GLES20.glViewport(0, 0, outputSurface.width, outputSurface.height)
            GLES20.glScissor(0, 0, outputSurface.width, outputSurface.height)
        }

        // TODO(b/245855601): Upload the matrix to GPU when textureTransform is changed.
        // Copy the texture transformation matrix over.
        GLES20.glUniformMatrix4fv(
            mTexMatrixLoc,  /*count=*/1,  /*transpose=*/false, textureTransform,  /*offset=*/
            0
        )
        checkGlErrorOrThrow("glUniformMatrix4fv")

        // Draw the rect.
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,  /*firstVertex=*/0,  /*vertexCount=*/4)
        checkGlErrorOrThrow("glDrawArrays")

        // Set timestamp
        EGLExt.eglPresentationTimeANDROID(mEglDisplay, outputSurface!!.eglSurface, timestampNs)

        // Swap buffer
        if (!EGL14.eglSwapBuffers(mEglDisplay, outputSurface.eglSurface)) {
            Log.w(
                TAG, "Failed to swap buffers with EGL error: 0x" + Integer.toHexString(
                    EGL14.eglGetError()
                )
            )
            removeOutputSurfaceInternal(surface, false)
        }
    }


    /**
     * Takes a snapshot of the current external texture and returns a Bitmap.
     *
     * @param size             the size of the output [Bitmap].
     * @param textureTransform the transformation matrix.
     * See: [SurfaceOutput.updateTransformMatrix]
     */
    //    @NonNull
    //    public Bitmap snapshot(@NonNull Size size, @NonNull float[] textureTransform) {
    //        // Allocate buffer.
    //        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(
    //                size.getWidth() * size.getHeight() * PIXEL_STRIDE);
    //        // Take a snapshot.
    //        snapshot(byteBuffer, size, textureTransform);
    //        // Create a Bitmap and copy the bytes over.
    //        Bitmap bitmap = Bitmap.createBitmap(
    //                size.getWidth(), size.getHeight(), Bitmap.Config.ARGB_8888);
    //        byteBuffer.rewind();
    //        copyByteBufferToBitmap(bitmap, byteBuffer, size.getWidth() * PIXEL_STRIDE);
    //        return bitmap;
    //    }
    /**
     * Copies information from a ByteBuffer to the address of the Bitmap
     *
     * @param bitmap            destination Bitmap
     * @param byteBuffer        source ByteBuffer
     * @param bufferStride      the stride of the ByteBuffer
     */
    //    public static void copyByteBufferToBitmap(@NonNull Bitmap bitmap,
    //                                              @NonNull ByteBuffer byteBuffer, int bufferStride) {
    //        int bitmapStride = bitmap.getRowBytes();
    //        int width = bitmap.getWidth();
    //        int height = bitmap.getHeight();
    //        nativeCopyBetweenByteBufferAndBitmap(bitmap, byteBuffer, bufferStride, bitmapStride, width,
    //                height, true);
    //    }
    /**
     * Takes a snapshot of the current external texture and stores it in the given byte buffer.
     *
     *
     *  The image is stored as RGBA with pixel stride of 4 bytes and row stride of width * 4
     * bytes.
     *
     * @param byteBuffer       the byte buffer to store the snapshot.
     * @param size             the size of the output image.
     * @param textureTransform the transformation matrix.
     * See: [SurfaceOutput.updateTransformMatrix]
     */
    private fun snapshot(
        byteBuffer: ByteBuffer, size: Size,
        textureTransform: FloatArray
    ) {
        checkArgument(
            byteBuffer.capacity() == size.width * size.height * 4,
            "ByteBuffer capacity is not equal to width * height * 4."
        )
        checkArgument(byteBuffer.isDirect, "ByteBuffer is not direct.")

        // Create and initialize intermediate texture.
        val texture = generateTexture()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        checkGlErrorOrThrow("glActiveTexture")
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        checkGlErrorOrThrow("glBindTexture")
        // Configure the texture.
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB, size.width,
            size.height, 0, GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE, null
        )
        checkGlErrorOrThrow("glTexImage2D")
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
        )

        // Create FBO.
        val fbo = generateFbo()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        checkGlErrorOrThrow("glBindFramebuffer")

        // Attach the intermediate texture to the FBO
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, texture, 0
        )
        checkGlErrorOrThrow("glFramebufferTexture2D")

        // Bind external texture (camera output).
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        checkGlErrorOrThrow("glActiveTexture")
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mExternalTextureId)
        checkGlErrorOrThrow("glBindTexture")

        // Set scissor and viewport.
        mCurrentSurface = null
        GLES20.glViewport(0, 0, size.width, size.height)
        GLES20.glScissor(0, 0, size.width, size.height)

        // Upload transform matrix.
        GLES20.glUniformMatrix4fv(
            mTexMatrixLoc,  /*count=*/1,  /*transpose=*/false, textureTransform,  /*offset=*/
            0
        )
        checkGlErrorOrThrow("glUniformMatrix4fv")

        // Draw the external texture to the intermediate texture.
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,  /*firstVertex=*/0,  /*vertexCount=*/4)
        checkGlErrorOrThrow("glDrawArrays")

        // Read the pixels from the framebuffer
        GLES20.glReadPixels(
            0, 0, size.width, size.height, GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            byteBuffer
        )
        checkGlErrorOrThrow("glReadPixels")

        // Clean up
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        deleteTexture(texture)
        deleteFbo(fbo)
        // Set the external texture to be active.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mExternalTextureId)
    }

    private fun getGlExtensionsBeforeInitialized(
        dynamicRangeToInitialize: DynamicRange
    ): String {
        checkInitializedOrThrow(false)
        return try {
            createEglContext(dynamicRangeToInitialize)
            createTempSurface()
            makeCurrent(mTempSurface)
            // eglMakeCurrent() has to be called before checking GL_EXTENSIONS.
            val glExtensions = GLES20.glGetString(GLES20.GL_EXTENSIONS)
            glExtensions ?: ""
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Failed to get GL extensions: " + e.message, e)
            ""
        } finally {
            releaseInternal()
        }
    }

    private fun createEglContext(dynamicRange: DynamicRange) {
        mEglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(mEglDisplay != EGL14.EGL_NO_DISPLAY) { "Unable to get EGL14 display" }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(mEglDisplay, version, 0, version, 1)) {
            mEglDisplay = EGL14.EGL_NO_DISPLAY
            throw IllegalStateException("Unable to initialize EGL14")
        }
        val rgbBits = if (is10BitHdr(dynamicRange)) 10 else 8
        val alphaBits = if (is10BitHdr(dynamicRange)) 2 else 8
        val renderType =
            if (is10BitHdr(dynamicRange)) EGLExt.EGL_OPENGL_ES3_BIT_KHR else EGL14.EGL_OPENGL_ES2_BIT
        // recordableAndroid with EGL14.EGL_TRUE causes eglError for 10BitHdr.
        val recordableAndroid = if (is10BitHdr(dynamicRange)) EGL14.EGL_FALSE else EGL14.EGL_TRUE
        val attribToChooseConfig = intArrayOf(
            EGL14.EGL_RED_SIZE, rgbBits,
            EGL14.EGL_GREEN_SIZE, rgbBits,
            EGL14.EGL_BLUE_SIZE, rgbBits,
            EGL14.EGL_ALPHA_SIZE, alphaBits,
            EGL14.EGL_DEPTH_SIZE, 0,
            EGL14.EGL_STENCIL_SIZE, 0,
            EGL14.EGL_RENDERABLE_TYPE, renderType,
            EGLExt.EGL_RECORDABLE_ANDROID, recordableAndroid,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        check(
            EGL14.eglChooseConfig(
                mEglDisplay, attribToChooseConfig, 0, configs, 0, configs.size,
                numConfigs, 0
            )
        ) { "Unable to find a suitable EGLConfig" }
        val config = configs[0]
        val attribToCreateContext = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, if (is10BitHdr(dynamicRange)) 3 else 2,
            EGL14.EGL_NONE
        )
        val context = EGL14.eglCreateContext(
            mEglDisplay, config, EGL14.EGL_NO_CONTEXT,
            attribToCreateContext, 0
        )
        checkEglErrorOrThrow("eglCreateContext")
        mEglConfig = config
        mEglContext = context

        // Confirm with query.
        val values = IntArray(1)
        EGL14.eglQueryContext(
            mEglDisplay, mEglContext, EGL14.EGL_CONTEXT_CLIENT_VERSION, values,
            0
        )
        Log.d(TAG, "EGLContext created, client version " + values[0])
    }

    private fun createTempSurface() {
        mTempSurface = createPBufferSurface(
            mEglDisplay, Objects.requireNonNull(mEglConfig)!!,  /*width=*/1,  /*height=*/
            1
        )
    }

    private fun makeCurrent(eglSurface: EGLSurface) {
        checkNotNull(mEglDisplay)
        checkNotNull(mEglContext)
        check(
            EGL14.eglMakeCurrent(
                mEglDisplay,
                eglSurface,
                eglSurface,
                mEglContext
            )
        ) { "eglMakeCurrent failed" }
    }

    private fun createProgram(
        dynamicRange: DynamicRange,
        shaderProvider: ShaderProvider
    ) {
        var vertexShader = -1
        var fragmentShader = -1
        var program = -1
        try {
            vertexShader = loadShader(
                GLES20.GL_VERTEX_SHADER,
                if (is10BitHdr(dynamicRange)) HDR_VERTEX_SHADER else DEFAULT_VERTEX_SHADER
            )
            fragmentShader = loadFragmentShader(dynamicRange, shaderProvider)
            program = GLES20.glCreateProgram()
            checkGlErrorOrThrow("glCreateProgram")
            GLES20.glAttachShader(program, vertexShader)
            checkGlErrorOrThrow("glAttachShader")
            GLES20.glAttachShader(program, fragmentShader)
            checkGlErrorOrThrow("glAttachShader")
            GLES20.glLinkProgram(program)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus,  /*offset=*/0)
            check(linkStatus[0] == GLES20.GL_TRUE) {
                "Could not link program: " + GLES20.glGetProgramInfoLog(
                    program
                )
            }
            mProgramHandle = program
        } catch (e: IllegalStateException) {
            if (vertexShader != -1) {
                GLES20.glDeleteShader(vertexShader)
            }
            if (fragmentShader != -1) {
                GLES20.glDeleteShader(fragmentShader)
            }
            if (program != -1) {
                GLES20.glDeleteProgram(program)
            }
            throw e
        } catch (e: IllegalArgumentException) {
            if (vertexShader != -1) {
                GLES20.glDeleteShader(vertexShader)
            }
            if (fragmentShader != -1) {
                GLES20.glDeleteShader(fragmentShader)
            }
            if (program != -1) {
                GLES20.glDeleteProgram(program)
            }
            throw e
        }
    }

    private fun useAndConfigureProgram() {
        // Select the program.
        GLES20.glUseProgram(mProgramHandle)
        checkGlErrorOrThrow("glUseProgram")

        // Set the texture.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mExternalTextureId)

        // Enable the "aPosition" vertex attribute.
        GLES20.glEnableVertexAttribArray(mPositionLoc)
        checkGlErrorOrThrow("glEnableVertexAttribArray")

        // Connect vertexBuffer to "aPosition".
        val coordsPerVertex = 2
        val vertexStride = 0
        GLES20.glVertexAttribPointer(
            mPositionLoc, coordsPerVertex, GLES20.GL_FLOAT,  /*normalized=*/
            false, vertexStride, VERTEX_BUF
        )
        checkGlErrorOrThrow("glVertexAttribPointer")

        // Enable the "aTextureCoord" vertex attribute.
        GLES20.glEnableVertexAttribArray(mTexCoordLoc)
        checkGlErrorOrThrow("glEnableVertexAttribArray")

        // Connect texBuffer to "aTextureCoord".
        val coordsPerTex = 2
        val texStride = 0
        GLES20.glVertexAttribPointer(
            mTexCoordLoc, coordsPerTex, GLES20.GL_FLOAT,  /*normalized=*/
            false, texStride, TEX_BUF
        )
        checkGlErrorOrThrow("glVertexAttribPointer")
    }

    private fun loadLocations() {
        mPositionLoc = GLES20.glGetAttribLocation(mProgramHandle, "aPosition")
        checkLocationOrThrow(mPositionLoc, "aPosition")
        mTexCoordLoc = GLES20.glGetAttribLocation(mProgramHandle, "aTextureCoord")
        checkLocationOrThrow(mTexCoordLoc, "aTextureCoord")
        mTexMatrixLoc = GLES20.glGetUniformLocation(mProgramHandle, "uTexMatrix")
        checkLocationOrThrow(mTexMatrixLoc, "uTexMatrix")
    }

    private fun createTexture() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        checkGlErrorOrThrow("glGenTextures")
        val texId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        checkGlErrorOrThrow("glBindTexture $texId")
        GLES20.glTexParameterf(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_NEAREST.toFloat()
        )
        GLES20.glTexParameterf(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR.toFloat()
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )
        checkGlErrorOrThrow("glTexParameter")
        mExternalTextureId = texId
    }

    private fun loadFragmentShader(
        dynamicRange: DynamicRange,
        shaderProvider: ShaderProvider
    ): Int {
        return if (shaderProvider === ShaderProvider.DEFAULT) {
            loadShader(
                GLES20.GL_FRAGMENT_SHADER,
                if (is10BitHdr(dynamicRange)) HDR_FRAGMENT_SHADER else DEFAULT_FRAGMENT_SHADER
            )
        } else {
            // Throw IllegalArgumentException if the shader provider can not provide a valid
            // fragment shader.
            val source: String?
            try {
                source = shaderProvider.createFragmentShader(VAR_TEXTURE, VAR_TEXTURE_COORD)
                // A simple check to workaround custom shader doesn't contain required variable.
                // See b/241193761.
                require(
                    !(source == null || !source.contains(VAR_TEXTURE_COORD) || !source.contains(
                        VAR_TEXTURE
                    ))
                ) { "Invalid fragment shader" }
                loadShader(GLES20.GL_FRAGMENT_SHADER, source)
            } catch (t: Throwable) {
                if (t is IllegalArgumentException) {
                    throw t
                }
                throw IllegalArgumentException("Unable to compile fragment shader", t)
            }
        }
    }

    private fun getSurfaceSize(eglSurface: EGLSurface): Size {
        val width = querySurface(mEglDisplay, eglSurface, EGL14.EGL_WIDTH)
        val height = querySurface(mEglDisplay, eglSurface, EGL14.EGL_HEIGHT)
        return Size(width, height)
    }

    private fun releaseInternal() {
        // Delete program
        if (mProgramHandle != -1) {
            GLES20.glDeleteProgram(mProgramHandle)
            mProgramHandle = -1
        }
        if (mEglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                mEglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )

            // Destroy EGLSurfaces
            for (outputSurface in mOutputSurfaceMap.values) {
                if (outputSurface.eglSurface != EGL14.EGL_NO_SURFACE) {
                    if (!EGL14.eglDestroySurface(mEglDisplay, outputSurface.eglSurface)) {
                        checkEglErrorOrLog("eglDestroySurface")
                    }
                }
            }
            mOutputSurfaceMap.clear()

            // Destroy temp surface
            if (mTempSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(mEglDisplay, mTempSurface)
                mTempSurface = EGL14.EGL_NO_SURFACE
            }

            // Destroy EGLContext and terminate display
            if (mEglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(mEglDisplay, mEglContext)
                mEglContext = EGL14.EGL_NO_CONTEXT
            }
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(mEglDisplay)
            mEglDisplay = EGL14.EGL_NO_DISPLAY
        }

        // Reset other members
        mEglConfig = null
        mProgramHandle = -1
        mTexMatrixLoc = -1
        mPositionLoc = -1
        mTexCoordLoc = -1
        mExternalTextureId = -1
        mCurrentSurface = null
        mGlThread = null
    }

    private fun checkInitializedOrThrow(shouldInitialized: Boolean) {
        val result = shouldInitialized == mInitialized.get()
        val message =
            if (shouldInitialized) "OpenGlRenderer is not initialized" else "OpenGlRenderer is already initialized"
        checkState(result, message)
    }

    private fun checkGlThreadOrThrow() {
        checkState(
            mGlThread === Thread.currentThread(),
            "Method call must be called on the GL thread."
        )
    }

    private fun getOutSurfaceOrThrow(surface: Surface): OutputSurface {
        checkState(
            mOutputSurfaceMap.containsKey(surface),
            "The surface is not registered."
        )
        return Objects.requireNonNull(
            mOutputSurfaceMap[surface]
        )!!
    }

    private fun createOutputSurfaceInternal(surface: Surface): OutputSurface? {
        val eglSurface: EGLSurface
        eglSurface = try {
            createWindowSurface(mEglDisplay, Objects.requireNonNull(mEglConfig)!!, surface)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Failed to create EGL surface: " + e.message, e)
            return null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Failed to create EGL surface: " + e.message, e)
            return null
        }
        val size = getSurfaceSize(eglSurface)
        return OutputSurface(eglSurface, size.width, size.height)
    }

    private fun removeOutputSurfaceInternal(surface: Surface, unregister: Boolean) {
        // Unmake current surface.
        if (mCurrentSurface === surface) {
            mCurrentSurface = null
            makeCurrent(mTempSurface)
        }

        // Remove cached EGL surface.
        val removedOutputSurface: OutputSurface?
        removedOutputSurface = if (unregister) {
            mOutputSurfaceMap.remove(surface)
        } else {
            mOutputSurfaceMap.put(surface, NO_OUTPUT_SURFACE)
        }

        // Destroy EGL surface.
        if (removedOutputSurface != null && removedOutputSurface !== NO_OUTPUT_SURFACE) {
            try {
                EGL14.eglDestroySurface(mEglDisplay, removedOutputSurface.eglSurface)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Failed to destroy EGL surface: " + e.message, e)
            }
        }
    }

    class OutputSurface(var eglSurface: EGLSurface, var width: Int, var height: Int)
    companion object {
        private const val TAG = "OpenGlRenderer"
        private const val VAR_TEXTURE_COORD = "vTextureCoord"
        private const val VAR_TEXTURE = "sTexture"
        private const val PIXEL_STRIDE = 4
        private val DEFAULT_VERTEX_SHADER = String.format(
            Locale.US,
            """uniform mat4 uTexMatrix;
attribute vec4 aPosition;
attribute vec4 aTextureCoord;
varying vec2 %s;
void main() {
    gl_Position = aPosition;
    %s = (uTexMatrix * aTextureCoord).xy;
}
""", VAR_TEXTURE_COORD, VAR_TEXTURE_COORD
        )
        private val HDR_VERTEX_SHADER = String.format(
            Locale.US,
            """#version 300 es
in vec4 aPosition;
in vec4 aTextureCoord;
uniform mat4 uTexMatrix;
out vec2 %s;
void main() {
  gl_Position = aPosition;
  %s = (uTexMatrix * aTextureCoord).xy;
}
""", VAR_TEXTURE_COORD, VAR_TEXTURE_COORD
        )
        private val DEFAULT_FRAGMENT_SHADER = String.format(
            Locale.US,
            """#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 %s;
uniform samplerExternalOES %s;
void main() {
    gl_FragColor = texture2D(%s, %s);
}
""", VAR_TEXTURE_COORD, VAR_TEXTURE, VAR_TEXTURE, VAR_TEXTURE_COORD
        )
        private val HDR_FRAGMENT_SHADER = String.format(
            Locale.US,
            """#version 300 es
#extension GL_OES_EGL_image_external : require
#extension GL_EXT_YUV_target : require
precision mediump float;
uniform __samplerExternal2DY2YEXT %s;
in vec2 %s;
out vec4 outColor;

vec3 yuvToRgb(vec3 yuv) {
  const vec3 yuvOffset = vec3(0.0625, 0.5, 0.5);
  const mat3 yuvToRgbColorTransform = mat3(
    1.1689f, 1.1689f, 1.1689f,
    0.0000f, -0.1881f, 2.1502f,
    1.6853f, -0.6530f, 0.0000f
  );
  return clamp(yuvToRgbColorTransform * (yuv - yuvOffset), 0.0, 1.0);
}

void main() {
  vec3 srcYuv = texture(%s, %s).xyz;
  outColor = vec4(yuvToRgb(srcYuv), 1.0);
}""", VAR_TEXTURE, VAR_TEXTURE_COORD, VAR_TEXTURE, VAR_TEXTURE_COORD
        )
        private val VERTEX_COORDS = floatArrayOf(
            -1.0f, -1.0f,  // 0 bottom left
            1.0f, -1.0f,  // 1 bottom right
            -1.0f, 1.0f,  // 2 top left
            1.0f, 1.0f
        )
        private val VERTEX_BUF = createFloatBuffer(VERTEX_COORDS)
        private val TEX_COORDS = floatArrayOf(
            0.0f, 0.0f,  // 0 bottom left
            1.0f, 0.0f,  // 1 bottom right
            0.0f, 1.0f,  // 2 top left
            1.0f, 1.0f // 3 top right
        )
        private val TEX_BUF = createFloatBuffer(TEX_COORDS)
        private const val SIZEOF_FLOAT = 4
        private val NO_OUTPUT_SURFACE = OutputSurface(EGL14.EGL_NO_SURFACE, 0, 0)

        /**
         * Ensures that an expression checking an argument is true.
         *
         * @param expression the expression to check
         * @param errorMessage the exception message to use if the check fails; will
         * be converted to a string using [String.valueOf]
         * @throws IllegalArgumentException if `expression` is false
         */
        fun checkArgument(expression: Boolean, errorMessage: Any) {
            require(expression) { errorMessage.toString() }
        }

        private fun generateFbo(): Int {
            val fbos = IntArray(1)
            GLES20.glGenFramebuffers(1, fbos, 0)
            checkGlErrorOrThrow("glGenFramebuffers")
            return fbos[0]
        }

        private fun generateTexture(): Int {
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            checkGlErrorOrThrow("glGenTextures")
            return textures[0]
        }

        private fun deleteTexture(texture: Int) {
            val textures = intArrayOf(texture)
            GLES20.glDeleteTextures(1, textures, 0)
            checkGlErrorOrThrow("glDeleteTextures")
        }

        private fun deleteFbo(fbo: Int) {
            val fbos = intArrayOf(fbo)
            GLES20.glDeleteFramebuffers(1, fbos, 0)
            checkGlErrorOrThrow("glDeleteFramebuffers")
        }

        /**
         * Ensures that an object reference passed as a parameter to the calling
         * method is not null.
         *
         * @param reference an object reference
         * @return the non-null reference that was validated
         * @throws NullPointerException if `reference` is null
         */
        fun <T> checkNotNull(reference: T?): T {
            if (reference == null) {
                throw NullPointerException()
            }
            return reference
        }

        /**
         * Ensures the truth of an expression involving the state of the calling
         * instance, but not involving any parameters to the calling method.
         *
         * @param expression a boolean expression
         * @param message exception message
         * @throws IllegalStateException if `expression` is false
         */
        fun checkState(expression: Boolean, message: String?) {
            check(expression) { message!! }
        }

        private fun createPBufferSurface(
            eglDisplay: EGLDisplay,
            eglConfig: EGLConfig, width: Int, height: Int
        ): EGLSurface {
            val surfaceAttrib = intArrayOf(
                EGL14.EGL_WIDTH, width,
                EGL14.EGL_HEIGHT, height,
                EGL14.EGL_NONE
            )
            val eglSurface = EGL14.eglCreatePbufferSurface(
                eglDisplay, eglConfig, surfaceAttrib,  /*offset=*/
                0
            )
            checkEglErrorOrThrow("eglCreatePbufferSurface")
            checkNotNull(eglSurface) { "surface was null" }
            return eglSurface
        }

        private fun createWindowSurface(
            eglDisplay: EGLDisplay,
            eglConfig: EGLConfig, surface: Surface
        ): EGLSurface {
            // Create a window surface, and attach it to the Surface we received.
            val surfaceAttrib = intArrayOf(
                EGL14.EGL_NONE
            )
            val eglSurface = EGL14.eglCreateWindowSurface(
                eglDisplay, eglConfig, surface,
                surfaceAttrib,  /*offset=*/0
            )
            checkEglErrorOrThrow("eglCreateWindowSurface")
            checkNotNull(eglSurface) { "surface was null" }
            return eglSurface
        }

        private fun loadShader(shaderType: Int, source: String): Int {
            val shader = GLES20.glCreateShader(shaderType)
            checkGlErrorOrThrow("glCreateShader type=$shaderType")
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled,  /*offset=*/0)
            if (compiled[0] == 0) {
                Log.w(TAG, "Could not compile shader: $source")
                GLES20.glDeleteShader(shader)
                throw IllegalStateException(
                    "Could not compile shader type $shaderType:" + GLES20.glGetShaderInfoLog(
                        shader
                    )
                )
            }
            return shader
        }

        private fun querySurface(
            eglDisplay: EGLDisplay, eglSurface: EGLSurface,
            what: Int
        ): Int {
            val value = IntArray(1)
            EGL14.eglQuerySurface(eglDisplay, eglSurface, what, value,  /*offset=*/0)
            return value[0]
        }

        fun createFloatBuffer(coords: FloatArray): FloatBuffer {
            val bb = ByteBuffer.allocateDirect(coords.size * SIZEOF_FLOAT)
            bb.order(ByteOrder.nativeOrder())
            val fb = bb.asFloatBuffer()
            fb.put(coords)
            fb.position(0)
            return fb
        }

        private fun checkLocationOrThrow(location: Int, label: String) {
            check(location >= 0) { "Unable to locate '$label' in program" }
        }

        private fun checkEglErrorOrThrow(op: String) {
            val error = EGL14.eglGetError()
            check(error == EGL14.EGL_SUCCESS) {
                "$op: EGL error: 0x" + Integer.toHexString(
                    error
                )
            }
        }

        private fun checkEglErrorOrLog(op: String) {
            try {
                checkEglErrorOrThrow(op)
            } catch (e: IllegalStateException) {
                Log.e(TAG, e.toString(), e)
            }
        }

        private fun checkGlErrorOrThrow(op: String) {
            val error = GLES20.glGetError()
            check(error == GLES20.GL_NO_ERROR) {
                "$op: GL error 0x" + Integer.toHexString(
                    error
                )
            }
        }
    }
}