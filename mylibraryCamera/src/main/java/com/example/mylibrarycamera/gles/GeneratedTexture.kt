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

import android.opengl.GLES20
import java.nio.ByteBuffer

/**
 * Code for generating images useful for testing textures.
 */
object GeneratedTexture {
    // Basic colors, in little-endian RGBA.
    private const val BLACK = 0x00000000
    private const val RED = 0x000000ff
    private const val GREEN = 0x0000ff00
    private const val BLUE = 0x00ff0000
    private const val MAGENTA = RED or BLUE
    private const val YELLOW = RED or GREEN
    private const val CYAN = GREEN or BLUE
    private const val WHITE = RED or GREEN or BLUE
    private const val OPAQUE = 0xff000000L.toInt()
    private const val HALF = 0x80000000L.toInt()
    private const val LOW = 0x40000000L.toInt()
    private const val TRANSP = 0
    private val GRID = intArrayOf( // must be 16 elements
        OPAQUE or RED, OPAQUE or YELLOW, OPAQUE or GREEN, OPAQUE or MAGENTA,
        OPAQUE or WHITE, LOW or RED, LOW or GREEN, OPAQUE or YELLOW,
        OPAQUE or MAGENTA, TRANSP or GREEN, HALF or RED, OPAQUE or BLACK,
        OPAQUE or CYAN, OPAQUE or MAGENTA, OPAQUE or CYAN, OPAQUE or BLUE
    )
    private const val TEX_SIZE = 64 // must be power of 2
    private const val FORMAT = GLES20.GL_RGBA
    private const val BYTES_PER_PIXEL = 4 // RGBA

    // Generate test image data.  This must come after the other values are initialized.
    private val sCoarseImageData = generateCoarseData()
    private val sFineImageData = generateFineData()

    /**
     * Creates a test texture in the current GL context.
     *
     *
     * This follows image conventions, so the pixel data at offset zero is intended to appear
     * in the top-left corner.  Color values for non-opaque alpha will be pre-multiplied.
     *
     * @return Handle to texture.
     */
    fun createTestTexture(which: Image?): Int {
        val buf: ByteBuffer
        buf = when (which) {
            Image.COARSE -> sCoarseImageData
            Image.FINE -> sFineImageData
            else -> throw RuntimeException("unknown image")
        }
        return GlUtil.createImageTexture(buf, TEX_SIZE, TEX_SIZE, FORMAT)
    }

    /**
     * Generates a "coarse" test image.  We want to create a 4x4 block pattern with obvious color
     * values in the corners, so that we can confirm orientation and coverage.  We also
     * leave a couple of alpha holes to check that channel.  Single pixels are set in two of
     * the corners to make it easy to see if we're cutting the texture off at the edge.
     *
     *
     * Like most image formats, the pixel data begins with the top-left corner, which is
     * upside-down relative to OpenGL conventions.  The texture coordinates should be flipped
     * vertically.  Using an asymmetric patterns lets us check that we're doing that right.
     *
     *
     * Colors use pre-multiplied alpha (so set glBlendFunc appropriately).
     *
     * @return A direct ByteBuffer with the 8888 RGBA data.
     */
    private fun generateCoarseData(): ByteBuffer {
        val buf = ByteArray(TEX_SIZE * TEX_SIZE * BYTES_PER_PIXEL)
        val scale = TEX_SIZE / 4 // convert 64x64 --> 4x4
        var i = 0
        while (i < buf.size) {
            val texRow = i / BYTES_PER_PIXEL / TEX_SIZE
            val texCol = i / BYTES_PER_PIXEL % TEX_SIZE
            val gridRow = texRow / scale // 0-3
            val gridCol = texCol / scale // 0-3
            val gridIndex = gridRow * 4 + gridCol // 0-15
            var color = GRID[gridIndex]

            // override the pixels in two corners to check coverage
            if (i == 0) {
                color = OPAQUE or WHITE
            } else if (i == buf.size - BYTES_PER_PIXEL) {
                color = OPAQUE or WHITE
            }

            // extract RGBA; use "int" instead of "byte" to get unsigned values
            val red = color and 0xff
            val green = color shr 8 and 0xff
            val blue = color shr 16 and 0xff
            val alpha = color shr 24 and 0xff

            // pre-multiply colors and store in buffer
            val alphaM = alpha / 255.0f
            buf[i] = (red * alphaM).toInt().toByte()
            buf[i + 1] = (green * alphaM).toInt().toByte()
            buf[i + 2] = (blue * alphaM).toInt().toByte()
            buf[i + 3] = alpha.toByte()
            i += BYTES_PER_PIXEL
        }
        val byteBuf = ByteBuffer.allocateDirect(buf.size)
        byteBuf.put(buf)
        byteBuf.position(0)
        return byteBuf
    }

    /**
     * Generates a fine-grained test image.
     *
     * @return A direct ByteBuffer with the 8888 RGBA data.
     */
    private fun generateFineData(): ByteBuffer {
        val buf = ByteArray(TEX_SIZE * TEX_SIZE * BYTES_PER_PIXEL)

        // top/left: single-pixel red/blue
        checkerPattern(
            buf, 0, 0, TEX_SIZE / 2, TEX_SIZE / 2,
            OPAQUE or RED, OPAQUE or BLUE, 0x01
        )
        // bottom/right: two-pixel red/green
        checkerPattern(
            buf, TEX_SIZE / 2, TEX_SIZE / 2, TEX_SIZE, TEX_SIZE,
            OPAQUE or RED, OPAQUE or GREEN, 0x02
        )
        // bottom/left: four-pixel blue/green
        checkerPattern(
            buf, 0, TEX_SIZE / 2, TEX_SIZE / 2, TEX_SIZE,
            OPAQUE or BLUE, OPAQUE or GREEN, 0x04
        )
        // top/right: eight-pixel black/white
        checkerPattern(
            buf, TEX_SIZE / 2, 0, TEX_SIZE, TEX_SIZE / 2,
            OPAQUE or WHITE, OPAQUE or BLACK, 0x08
        )
        val byteBuf = ByteBuffer.allocateDirect(buf.size)
        byteBuf.put(buf)
        byteBuf.position(0)
        return byteBuf
    }

    private fun checkerPattern(
        buf: ByteArray, left: Int, top: Int, right: Int, bottom: Int,
        color1: Int, color2: Int, bit: Int
    ) {
        for (row in top until bottom) {
            val rowOffset = row * TEX_SIZE * BYTES_PER_PIXEL
            for (col in left until right) {
                val offset = rowOffset + col * BYTES_PER_PIXEL
                var color: Int
                color = if (row and bit xor (col and bit) == 0) {
                    color1
                } else {
                    color2
                }

                // extract RGBA; use "int" instead of "byte" to get unsigned values
                val red = color and 0xff
                val green = color shr 8 and 0xff
                val blue = color shr 16 and 0xff
                val alpha = color shr 24 and 0xff

                // pre-multiply colors and store in buffer
                val alphaM = alpha / 255.0f
                buf[offset] = (red * alphaM).toInt().toByte()
                buf[offset + 1] = (green * alphaM).toInt().toByte()
                buf[offset + 2] = (blue * alphaM).toInt().toByte()
                buf[offset + 3] = alpha.toByte()
            }
        }
    }

    //private static final String TAG = GlUtil.TAG;
    enum class Image {
        COARSE, FINE
    }
}