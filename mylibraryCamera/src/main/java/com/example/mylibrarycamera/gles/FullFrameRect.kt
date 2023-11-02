/*
 * Copyright 2014 Google Inc. All rights reserved.
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
package com.example.mylibrarycamera.gles

/**
 * This class essentially represents a viewport-sized sprite that will be rendered with
 * a texture, usually from an external source like the camera or video decoder.
 */
class FullFrameRect
/**
 * Prepares the object.
 *
 * @param program The program to use.  FullFrameRect takes ownership, and will release
 * the program when no longer needed.
 */(
    /**
     * Returns the program currently in use.
     */
    var program: Texture2dProgram?
) {
    private val mRectDrawable = Drawable2d(Drawable2d.Prefab.FULL_RECTANGLE)

    /**
     * Releases resources.
     *
     *
     * This must be called with the appropriate EGL context current (i.e. the one that was
     * current when the constructor was called).  If we're about to destroy the EGL context,
     * there's no value in having the caller make it current just to do this cleanup, so you
     * can pass a flag that will tell this function to skip any EGL-context-specific cleanup.
     */
    fun release(doEglCleanup: Boolean) {
        if (program != null) {
            if (doEglCleanup) {
                program!!.release()
            }
            program = null
        }
    }

    /**
     * Changes the program.  The previous program will be released.
     *
     *
     * The appropriate EGL context must be current.
     */
    fun changeProgram(program: Texture2dProgram?) {
        program!!.release()
        this.program = program
    }

    /**
     * Creates a texture object suitable for use with drawFrame().
     */
    fun createTextureObject(): Int {
        return program!!.createTextureObject()
    }

    /**
     * Draws a viewport-filling rect, texturing it with the specified texture object.
     */
    fun drawFrame(textureId: Int, texMatrix: FloatArray?) {
        // Use the identity matrix for MVP so our 2x2 FULL_RECTANGLE covers the viewport.
        program!!.draw(
            GlUtil.IDENTITY_MATRIX, mRectDrawable.vertexArray, 0,
            mRectDrawable.vertexCount, mRectDrawable.coordsPerVertex,
            mRectDrawable.vertexStride,
            texMatrix, mRectDrawable.texCoordArray, textureId,
            mRectDrawable.texCoordStride
        )
    }
}