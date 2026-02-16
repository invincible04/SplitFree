package com.splitfree.data.util

import com.splitfree.util.DebugLog as Log
import net.jpountz.lz4.LZ4Factory
import java.nio.ByteBuffer
import java.nio.ByteOrder

object CompressionUtil {
    private const val TAG = "CompressionUtil"
    private const val THRESHOLD = 100
    private const val MAX_RATIO = 1_000.0
    private const val MAX_OUTPUT_SIZE = 100_000 // 100KB — expense data should never exceed this
    private val factory = LZ4Factory.fastestInstance()

    fun shouldCompress(data: ByteArray): Boolean = data.size >= THRESHOLD

    fun compress(data: ByteArray): ByteArray? {
        return try {
            val compressor = factory.fastCompressor()
            val maxLen = compressor.maxCompressedLength(data.size)
            val buf = ByteArray(maxLen)
            val compressedLen = compressor.compress(data, 0, data.size, buf, 0, maxLen)
            if (compressedLen >= data.size) return null
            // Prepend 4-byte big-endian original size
            ByteBuffer
                .allocate(4 + compressedLen)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(data.size)
                .put(buf, 0, compressedLen)
                .array()
        } catch (e: Exception) {
            Log.w(TAG, "Compression failed: ${e.message}")
            null
        }
    }

    fun decompress(compressed: ByteArray): ByteArray? {
        if (compressed.size < 4) return null
        return try {
            val originalSize = ByteBuffer.wrap(compressed, 0, 4).order(ByteOrder.BIG_ENDIAN).int
            if (originalSize <= 0 || originalSize > MAX_OUTPUT_SIZE) return null
            val ratio = originalSize.toDouble() / (compressed.size - 4).toDouble()
            if (ratio > MAX_RATIO) {
                Log.w(TAG, "Suspicious ratio $ratio:1 — possible bomb")
                return null
            }
            val decompressor = factory.safeDecompressor()
            val restored = ByteArray(originalSize)
            decompressor.decompress(compressed, 4, compressed.size - 4, restored, 0)
            restored
        } catch (e: Exception) {
            Log.w(TAG, "Decompression failed: ${e.message}")
            null
        }
    }
}
