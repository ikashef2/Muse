package com.kashef.archive.data

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

data class AudioFingerprint(
    val encoded: String,
    val analyzedDurationSeconds: Int,
)

class ChromaprintEngine(private val context: Context) {
    companion object {
        init { System.loadLibrary("musefingerprint") }
        private const val TIMEOUT_US = 10_000L
        private const val MAX_ANALYSIS_US = 120_000_000L
    }

    suspend fun fingerprint(uri: Uri): AudioFingerprint = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        val handle = nativeCreate()
        require(handle != 0L) { "Chromaprint could not initialize." }
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No audio stream was found in this file.")
            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("The audio codec is unknown.")
            val sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            runCatching { inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) }
            check(nativeStart(handle, sampleRate, channels)) { "Chromaprint rejected the audio format." }

            decoder = MediaCodec.createDecoderByType(mime).apply {
                configure(inputFormat, null, null, 0)
                start()
            }
            val bufferInfo = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var analyzedUs = 0L

            while (!outputEnded && analyzedUs < MAX_ANALYSIS_US) {
                coroutineContext.ensureActive()
                if (!inputEnded) {
                    val inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex) ?: error("Decoder input unavailable.")
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outputIndex >= 0) {
                    val outputBuffer = decoder.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size >= 2) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val shorts = outputBuffer.slice().order(ByteOrder.nativeOrder()).asShortBuffer()
                        val samples = ShortArray(shorts.remaining())
                        shorts.get(samples)
                        check(nativeFeed(handle, samples, samples.size)) { "Chromaprint rejected decoded audio." }
                    }
                    analyzedUs = maxOf(analyzedUs, bufferInfo.presentationTimeUs)
                    outputEnded = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    decoder.releaseOutputBuffer(outputIndex, false)
                }
            }

            val encoded = nativeFinish(handle).orEmpty()
            check(encoded.isNotBlank()) { "The audio fingerprint was empty." }
            AudioFingerprint(encoded, (analyzedUs / 1_000_000L).toInt().coerceAtLeast(1))
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
            nativeDestroy(handle)
        }
    }

    private external fun nativeCreate(): Long
    private external fun nativeStart(handle: Long, sampleRate: Int, channels: Int): Boolean
    private external fun nativeFeed(handle: Long, samples: ShortArray, sampleCount: Int): Boolean
    private external fun nativeFinish(handle: Long): String?
    private external fun nativeDestroy(handle: Long)
}
