package com.splitfree.domain.util

/**
 * Domain contract for data compression (LZ4).
 * Provides LZ4 compression with decompression-bomb protection.
 */
interface CompressionProvider {
    /** @return true if [data] is large enough to benefit from compression */
    fun shouldCompress(data: ByteArray): Boolean

    /**
     * Compress [data] with a 4-byte big-endian original-size header.
     * @return compressed bytes, or null if compression doesn't reduce size
     */
    fun compress(data: ByteArray): ByteArray?

    /**
     * Decompress a payload previously produced by [compress].
     * @return decompressed bytes, or null if the payload is invalid or exceeds safety limits
     */
    fun decompress(compressed: ByteArray): ByteArray?
}
