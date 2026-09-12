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
        private const val TIMEOUT_US = 10_000L
        // Chromaprint does not need the whole song. A one-minute sample keeps
        // identification reliable without decoding two minutes on the phone.
        private const val MAX_ANALYSIS_US = 60_000_000L
    }

    private val nativeAvailable by lazy {
        runCatching { System.loadLibrary("musefingerprint") }.isSuccess
    }

    suspend fun fingerprint(uri: Uri): AudioFingerprint = withContext(Dispatchers.IO) {
        check(nativeAvailable) {
            "Audio fingerprinting is unavailable on this device. Muse will use catalog search instead."
        }
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
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

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
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val outputFormat = decoder.outputFormat
                    pcmEncoding = outputFormat.getIntegerOrDefault(
                        MediaFormat.KEY_PCM_ENCODING,
                        AudioFormat.ENCODING_PCM_16BIT,
                    )
                } else if (outputIndex >= 0) {
                    val outputBuffer = decoder.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size >= 2) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val pcm = outputBuffer.slice().order(ByteOrder.nativeOrder())
                        val samples = if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            val floats = pcm.asFloatBuffer()
                            ShortArray(floats.remaining()) { index ->
                                (floats.get(index).coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
                            }
                        } else {
                            val shorts = pcm.asShortBuffer()
                            ShortArray(shorts.remaining()).also { shorts.get(it) }
                        }
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

private fun MediaFormat.getIntegerOrDefault(key: String, fallback: Int): Int =
    runCatching { getInteger(key) }.getOrDefault(fallback)
