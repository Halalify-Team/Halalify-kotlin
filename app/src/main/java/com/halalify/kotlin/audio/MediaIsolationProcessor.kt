package com.halalify.kotlin.audio

import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import kotlin.math.floor
import kotlin.math.roundToInt

internal data class MediaIsolationRequest(
    val localUri: Uri?,
    val remoteUrl: String?,
    val displayName: String,
)

internal data class MediaIsolationResult(
    val outputUri: Uri,
    val outputName: String,
    val hasVideo: Boolean,
)

internal class MediaIsolationException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Offline media pipeline for the Isolation button.
 *
 * MediaCodec is used instead of a shell/FFmpeg dependency so the selected file never leaves
 * the device. The DTLN model already used by the playback monitor is fed one-second frames and
 * its speech stem is encoded as AAC. For a video source, the original video samples are copied
 * unchanged and only the audio track is replaced.
 */
internal class MediaIsolationProcessor(
    context: Context,
    private val onProgress: (Int) -> Unit = {},
) {
    private val appContext = context.applicationContext

    fun process(request: MediaIsolationRequest): MediaIsolationResult {
        val prepared = prepareInput(request)
        var audioFile: File? = null
        var finalFile: File? = null
        try {
            val sourceProbe = prepared.openExtractor(appContext)
            val audioTrack: Int
            val videoTrack: Int
            val audioFormat: MediaFormat
            try {
                audioTrack = findTrack(sourceProbe, "audio/")
                videoTrack = findTrack(sourceProbe, "video/")
                audioFormat = sourceProbe.getTrackFormat(audioTrack)
            } finally {
                sourceProbe.release()
            }
            val durationUs = audioFormat.longValue(MediaFormat.KEY_DURATION)

            audioFile = File.createTempFile("halalify-audio-", ".m4a", appContext.cacheDir)
            processAudio(prepared, audioTrack, audioFormat, durationUs, audioFile)

            val extension = if (videoTrack >= 0) ".mp4" else ".m4a"
            finalFile = File.createTempFile("halalify-result-", extension, appContext.cacheDir)
            muxResult(prepared, audioFile, videoTrack, finalFile)

            onProgress(98)
            val outputName = outputName(request.displayName, videoTrack >= 0)
            val outputUri = publish(finalFile, outputName, videoTrack >= 0)
            onProgress(100)
            return MediaIsolationResult(outputUri, outputName, videoTrack >= 0)
        } catch (error: MediaIsolationException) {
            throw error
        } catch (error: Exception) {
            throw MediaIsolationException(
                error.message ?: "Media isolation failed.",
                error,
            )
        } finally {
            runCatching { prepared.downloadedFile?.delete() }
            runCatching { audioFile?.delete() }
            runCatching { finalFile?.delete() }
        }
    }

    private fun prepareInput(request: MediaIsolationRequest): PreparedInput {
        val url = request.remoteUrl?.trim().orEmpty()
        if (url.isNotEmpty()) {
            validateRemoteUrl(url)
            onProgress(3)
            return PreparedInput(download(url))
        }
        val uri = request.localUri
            ?: throw MediaIsolationException("اختر ملفًا أو أدخل رابطًا صالحًا أولًا.")
        return PreparedInput(uri = uri)
    }

    private fun validateRemoteUrl(value: String) {
        val parsed = runCatching { URL(value) }.getOrNull()
            ?: throw MediaIsolationException("الرابط غير صالح.")
        if (parsed.protocol.lowercase(Locale.US) !in setOf("http", "https")) {
            throw MediaIsolationException("يسمح فقط بروابط http و https.")
        }
        val host = parsed.host.lowercase(Locale.US).removePrefix("www.")
        if (host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "youtu.be" || host == "music.youtube.com"
        ) {
            throw MediaIsolationException(
                "رابط YouTube هو صفحة وليست ملفًا صوتيًا مباشرًا. نزّل المقطع على الجهاز ثم اختره من Choose file، أو استخدم رابط MP4/M4A مباشر.",
            )
        }
    }

    private fun download(value: String): File {
        val connection = (URL(value).openConnection() as? HttpURLConnection)
            ?: throw MediaIsolationException("تعذر فتح الرابط.")
        val file = File(appContext.cacheDir, "halalify-source-${UUID.randomUUID()}.media")
        try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = NETWORK_TIMEOUT_MS
            connection.readTimeout = NETWORK_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.connect()
            val response = connection.responseCode
            if (response !in 200..299) {
                throw MediaIsolationException("فشل تنزيل الرابط (HTTP $response).")
            }
            val length = connection.contentLengthLong
            if (length > MAX_DOWNLOAD_BYTES) {
                throw MediaIsolationException("حجم الملف أكبر من الحد المسموح به (500 MB).")
            }
            val contentType = connection.contentType.orEmpty().lowercase(Locale.US)
            if (contentType.startsWith("text/html")) {
                throw MediaIsolationException(
                    "الرابط يعرض صفحة ويب وليس ملفًا إعلاميًا مباشرًا. استخدم رابط MP4/M4A مباشرًا أو اختر الملف من الجهاز.",
                )
            }
            connection.inputStream.use { input ->
                FileOutputStream(file).use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_DOWNLOAD_BYTES) {
                            throw MediaIsolationException("حجم الملف أكبر من الحد المسموح به (500 MB).")
                        }
                        output.write(buffer, 0, count)
                        if (length > 0) {
                            onProgress((3 + (total * 17 / length).toInt()).coerceAtMost(20))
                        }
                    }
                }
            }
            check(file.length() > 0) { "The downloaded media file is empty." }
            return file
        } catch (error: MediaIsolationException) {
            file.delete()
            throw error
        } catch (error: Exception) {
            file.delete()
            throw MediaIsolationException("تعذر تنزيل الرابط: ${error.message ?: error.javaClass.simpleName}.", error)
        } finally {
            connection.disconnect()
        }
    }

    private fun processAudio(
        source: PreparedInput,
        audioTrack: Int,
        audioFormat: MediaFormat,
        durationUs: Long,
        output: File,
    ) {
        val sampleRate = audioFormat.intValue(MediaFormat.KEY_SAMPLE_RATE)
        val channels = audioFormat.intValue(MediaFormat.KEY_CHANNEL_COUNT)
        if (sampleRate <= 0 || channels <= 0) {
            throw MediaIsolationException("تنسيق الصوت في الملف غير مدعوم.")
        }

        val selection = BundledAudioProcessorProvider(appContext).create()
        val processor = selection.processor
            ?: throw MediaIsolationException(
                "نموذج عزل الصوت غير متاح: ${selection.unavailableReason ?: "model unavailable"}.",
            )
        if (processor is YamnetDtlnAudioProcessor && !processor.isolationAvailable) {
            processor.close()
            throw MediaIsolationException("نموذج DTLN لعزل الموسيقى غير مثبت داخل التطبيق.")
        }

        val extractor = source.openExtractor(appContext)
        val decoder = MediaCodec.createDecoderByType(audioFormat.stringValue(MediaFormat.KEY_MIME))
        val encoder = SpeechStemEncoder(output, durationUs)
        val resampler = Pcm16MonoResampler(sampleRate, channels, TARGET_SAMPLE_RATE)
        val assembler = SpeechFrameAssembler(processor, encoder)
        val decoderInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var lastProgress = 20

        try {
            extractor.selectTrack(audioTrack)
            decoder.configure(audioFormat, null, null, 0)
            decoder.start()
            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)
                            ?: throw MediaIsolationException("تعذر تجهيز مخزن فك ترميز الصوت.")
                        inputBuffer.clear()
                        val size = extractor.readSampleData(inputBuffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            val presentationTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                            decoder.queueInputBuffer(inputIndex, 0, size, presentationTimeUs, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = decoder.dequeueOutputBuffer(decoderInfo, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> if (outputIndex >= 0) {
                        if (decoderInfo.size > 0 &&
                            decoderInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        ) {
                            val pcm = readPcm16(decoder.getOutputBuffer(outputIndex), decoderInfo)
                            assembler.append(resampler.append(pcm))
                            val inputDuration = if (durationUs > 0) durationUs else extractor.sampleTime
                            if (inputDuration > 0) {
                                val progress = (20 + (decoderInfo.presentationTimeUs * 55 / inputDuration).toInt())
                                    .coerceIn(20, 75)
                                if (progress > lastProgress) {
                                    lastProgress = progress
                                    onProgress(progress)
                                }
                            }
                        }
                        val endOfStream = decoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(outputIndex, false)
                        if (endOfStream) outputDone = true
                    }
                }
            }
            assembler.append(resampler.finish())
            assembler.finish()
            onProgress(80)
        } finally {
            runCatching { decoder.stop() }
            decoder.release()
            extractor.release()
            processor.close()
            encoder.close()
        }
    }

    private fun readPcm16(buffer: ByteBuffer?, info: MediaCodec.BufferInfo): ShortArray {
        if (buffer == null) throw MediaIsolationException("فك ترميز الصوت لم يُرجع بيانات PCM.")
        val duplicate = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val offset = info.offset.coerceAtLeast(0)
        val size = info.size.coerceAtMost(duplicate.capacity() - offset)
        if (size <= 0) return ShortArray(0)
        duplicate.position(offset)
        duplicate.limit(offset + size)
        val pcm = ShortArray(size / Short.SIZE_BYTES)
        for (index in pcm.indices) pcm[index] = duplicate.short
        return pcm
    }

    private fun muxResult(
        source: PreparedInput,
        audioFile: File,
        videoTrack: Int,
        output: File,
    ) {
        val audioExtractor = MediaExtractor()
        val sourceExtractor = source.openExtractor(appContext)
        var muxer: MediaMuxer? = null
        try {
            audioExtractor.setDataSource(audioFile.absolutePath)
            audioExtractor.selectTrack(0)
            val audioFormat = audioExtractor.getTrackFormat(0)
            val muxerInstance = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = muxerInstance
            val audioOutputTrack = muxerInstance.addTrack(audioFormat)
            val videoOutputTrack = if (videoTrack >= 0) {
                muxerInstance.addTrack(sourceExtractor.getTrackFormat(videoTrack))
            } else {
                -1
            }
            muxerInstance.start()

            val audioCursor = SampleCursor(audioExtractor, audioOutputTrack, audioFormat)
            audioCursor.currentMuxer = muxerInstance
            val videoCursor = if (videoTrack >= 0) {
                sourceExtractor.selectTrack(videoTrack)
                SampleCursor(sourceExtractor, videoOutputTrack, sourceExtractor.getTrackFormat(videoTrack)).also {
                    it.currentMuxer = muxerInstance
                }
            } else {
                null
            }
            while (audioCursor.hasSample || videoCursor?.hasSample == true) {
                val writeVideo = when {
                    !audioCursor.hasSample -> true
                    videoCursor?.hasSample != true -> false
                    else -> videoCursor.presentationTimeUs <= audioCursor.presentationTimeUs
                }
                if (writeVideo) {
                    videoCursor?.writeNext()
                } else {
                    audioCursor.writeNext()
                }
            }
            muxerInstance.stop()
            muxer = null
        } finally {
            runCatching { muxer?.stop() }
            muxer?.release()
            audioExtractor.release()
            sourceExtractor.release()
        }
        onProgress(92)
    }

    private fun publish(file: File, name: String, hasVideo: Boolean): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = if (hasVideo) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, if (hasVideo) "video/mp4" else "audio/mp4")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    if (hasVideo) {
                        Environment.DIRECTORY_MOVIES + "/Halalify"
                    } else {
                        Environment.DIRECTORY_MUSIC + "/Halalify"
                    },
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = appContext.contentResolver.insert(collection, values)
                ?: throw MediaIsolationException("تعذر إنشاء ملف الناتج في التخزين.")
            try {
                appContext.contentResolver.openOutputStream(uri)?.use { output ->
                    FileInputStream(file).use { input -> input.copyTo(output) }
                } ?: throw MediaIsolationException("تعذر كتابة ملف الناتج.")
                appContext.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
                return uri
            } catch (error: Exception) {
                appContext.contentResolver.delete(uri, null, null)
                throw error
            }
        }

        val directory = appContext.getExternalFilesDir(
            if (hasVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC,
        ) ?: appContext.filesDir
        val outputDirectory = File(directory, "Halalify").apply { mkdirs() }
        val target = File(outputDirectory, name)
        FileInputStream(file).use { input -> FileOutputStream(target).use { output -> input.copyTo(output) } }
        return Uri.fromFile(target)
    }

    private fun outputName(sourceName: String, hasVideo: Boolean): String {
        val base = sourceName.substringBeforeLast('.', sourceName)
            .replace(UNSAFE_NAME, "_")
            .trim('_')
            .ifBlank { "media" }
        val extension = if (hasVideo) "mp4" else "m4a"
        return "${base}_without_music.$extension"
    }

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).stringValue(MediaFormat.KEY_MIME)
            if (mime.startsWith(prefix)) return index
        }
        if (prefix == "audio/") {
            throw MediaIsolationException("لم يتم العثور على مسار صوت في الملف.")
        }
        return -1
    }

    private data class PreparedInput(
        val downloadedFile: File? = null,
        val uri: Uri? = null,
    ) {
        constructor(file: File) : this(downloadedFile = file, uri = Uri.fromFile(file))

        fun openExtractor(context: Context): MediaExtractor {
            val extractor = MediaExtractor()
            try {
                val localFile = downloadedFile
                if (localFile != null) {
                    extractor.setDataSource(localFile.absolutePath)
                } else {
                    extractor.setDataSource(context, checkNotNull(uri), emptyMap())
                }
                return extractor
            } catch (error: Exception) {
                extractor.release()
                throw MediaIsolationException("تعذر قراءة الملف الإعلامي: ${error.message ?: error.javaClass.simpleName}.", error)
            }
        }
    }

    private class SampleCursor(
        private val extractor: MediaExtractor,
        private val outputTrack: Int,
        format: MediaFormat,
    ) {
        private val buffer: ByteBuffer = ByteBuffer.allocateDirect(
            format.intValue(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(DEFAULT_SAMPLE_BUFFER_BYTES),
        )
        private val info = MediaCodec.BufferInfo()
        var hasSample: Boolean = false
            private set
        var presentationTimeUs: Long = Long.MAX_VALUE
            private set

        init {
            load()
        }

        fun writeNext() {
            if (!hasSample) return
            outputTrack.also { track ->
                // MediaExtractor writes from position zero; BufferInfo offsets are normalized.
                info.offset = 0
                buffer.position(0)
                buffer.limit(info.size)
                currentMuxer?.writeSampleData(track, buffer, info)
            }
            extractor.advance()
            load()
        }

        // Set by muxResult immediately before the cursor is used. Keeping this small helper
        // avoids making the cursor API expose MediaMuxer-specific state in its public contract.
        var currentMuxer: MediaMuxer? = null

        private fun load() {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) {
                hasSample = false
                presentationTimeUs = Long.MAX_VALUE
                return
            }
            info.set(0, size, extractor.sampleTime.coerceAtLeast(0L), extractor.sampleFlags)
            hasSample = true
            presentationTimeUs = info.presentationTimeUs
        }
    }

    private class Pcm16MonoResampler(
        private val inputSampleRate: Int,
        private val inputChannels: Int,
        private val outputSampleRate: Int,
    ) {
        private val step = inputSampleRate.toDouble() / outputSampleRate.toDouble()
        private var previousFrame: Float? = null
        private var nextPosition = 0.0

        fun append(interleavedPcm: ShortArray): ShortArray {
            if (interleavedPcm.isEmpty()) return ShortArray(0)
            val frameCount = interleavedPcm.size / inputChannels
            if (frameCount == 0) return ShortArray(0)
            val mono = FloatArray(frameCount)
            for (frame in 0 until frameCount) {
                var sum = 0F
                for (channel in 0 until inputChannels) {
                    sum += interleavedPcm[frame * inputChannels + channel] / 32768F
                }
                mono[frame] = sum / inputChannels
            }
            val samples = if (previousFrame == null) {
                mono
            } else {
                FloatArray(mono.size + 1).also {
                    it[0] = previousFrame ?: 0F
                    mono.copyInto(it, 1)
                }
            }
            val result = ByteArrayOutputStream()
            while (nextPosition + 1.0 < samples.size) {
                val index = floor(nextPosition).toInt()
                val fraction = (nextPosition - index).toFloat()
                val value = samples[index] * (1F - fraction) + samples[index + 1] * fraction
                val quantized = (value.coerceIn(-1F, 1F) * 32767F).roundToInt()
                result.write(quantized and 0xFF)
                result.write((quantized shr 8) and 0xFF)
                nextPosition += step
            }
            nextPosition -= samples.size - 1
            previousFrame = samples.last()
            val bytes = result.toByteArray()
            val output = ShortArray(bytes.size / 2)
            for (index in output.indices) {
                output[index] = ((bytes[index * 2].toInt() and 0xFF) or
                    (bytes[index * 2 + 1].toInt() shl 8)).toShort()
            }
            return output
        }

        fun finish(): ShortArray = previousFrame?.let {
            val result = append(shortArrayOf((it * 32767F).roundToInt().toShort()))
            previousFrame = null
            result
        } ?: ShortArray(0)
    }

    private class SpeechFrameAssembler(
        private val processor: AudioFrameProcessor,
        private val encoder: SpeechStemEncoder,
    ) {
        private val frame = ShortArray(processor.frameSamples)
        private var count = 0

        fun append(samples: ShortArray) {
            var offset = 0
            while (offset < samples.size) {
                val amount = minOf(frame.size - count, samples.size - offset)
                samples.copyInto(frame, count, offset, offset + amount)
                count += amount
                offset += amount
                if (count == frame.size) {
                    processFrame(frame)
                    count = 0
                }
            }
        }

        fun finish() {
            if (count > 0) {
                frame.fill(0, count, frame.size)
                processFrame(frame)
                count = 0
            }
            encoder.finish()
        }

        private fun processFrame(input: ShortArray) {
            val result = processor.process(input)
            if (!result.isolationActive) {
                throw MediaIsolationException("نموذج عزل الموسيقى غير متاح لمعالجة هذا الملف.")
            }
            encoder.append(restoreSpeechLevel(input, result.speechPcm))
        }

        /**
         * DTLN can return a valid speech stem at a much lower level than the source mix.
         * Without this compensation a correctly separated voice sounds silent on phones.
         * Gain is applied only to a non-silent stem and is capped to avoid bringing music
         * leakage or codec noise back to the foreground.
         */
        private fun restoreSpeechLevel(input: ShortArray, speech: ShortArray): ShortArray {
            val inputRms = rms(input)
            val speechRms = rms(speech)
            if (inputRms < MIN_ACTIVE_RMS || speechRms < MIN_STEM_RMS) return speech

            val targetRms = TARGET_STEM_RMS.coerceAtMost(inputRms * MAX_SOURCE_RATIO)
            val gain = (targetRms / speechRms).coerceIn(1F, MAX_STEM_GAIN)
            if (gain <= 1.01F) return speech
            return ShortArray(speech.size) { index ->
                (speech[index].toFloat() * gain)
                    .coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat())
                    .toInt()
                    .toShort()
            }
        }

        private fun rms(samples: ShortArray): Float {
            if (samples.isEmpty()) return 0F
            var sum = 0.0
            for (sample in samples) {
                val normalized = sample.toDouble() / Short.MAX_VALUE
                sum += normalized * normalized
            }
            return kotlin.math.sqrt(sum / samples.size).toFloat()
        }

        private companion object {
            const val MIN_ACTIVE_RMS = 0.002F
            const val MIN_STEM_RMS = 0.0005F
            const val TARGET_STEM_RMS = 0.08F
            const val MAX_SOURCE_RATIO = 0.70F
            const val MAX_STEM_GAIN = 8F
        }
    }

    private class SpeechStemEncoder(
        output: File,
        private val durationUs: Long,
    ) : AutoCloseable {
        private val codec = MediaCodec.createEncoderByType(AAC_MIME)
        private val outputFile = output
        private var muxer: MediaMuxer? = null
        private var outputTrack = -1
        private var inputSamples = 0L
        private var endOfStream = false
        private val info = MediaCodec.BufferInfo()

        init {
            val format = MediaFormat.createAudioFormat(AAC_MIME, TARGET_SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfoCompat.AAC_OBJECT_LC)
                setInteger(MediaFormat.KEY_BIT_RATE, AAC_BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, TARGET_FRAME_SAMPLES * 2)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        }

        fun append(samples: ShortArray) {
            var offset = 0
            while (offset < samples.size) {
                drain(false)
                val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (inputIndex < 0) continue
                val buffer = codec.getInputBuffer(inputIndex)
                    ?: throw MediaIsolationException("تعذر تجهيز مرمّز الصوت.")
                buffer.clear()
                val count = minOf(samples.size - offset, buffer.remaining() / 2)
                if (count <= 0) throw MediaIsolationException("مخزن مرمّز الصوت صغير جدًا.")
                for (index in 0 until count) buffer.putShort(samples[offset + index])
                val pts = inputSamples * 1_000_000L / TARGET_SAMPLE_RATE
                codec.queueInputBuffer(inputIndex, 0, count * 2, pts, 0)
                inputSamples += count
                offset += count
            }
        }

        fun finish() {
            if (endOfStream) return
            while (true) {
                val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (inputIndex >= 0) {
                    codec.queueInputBuffer(
                        inputIndex,
                        0,
                        0,
                        inputSamples * 1_000_000L / TARGET_SAMPLE_RATE,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                    )
                    break
                }
                drain(false)
            }
            while (!endOfStream) drain(true)
            muxer?.stop()
            muxer?.release()
            muxer = null
            if (outputTrack < 0) throw MediaIsolationException("مرمّز الصوت لم يُنتج مسارًا صالحًا.")
        }

        private fun drain(waitForOutput: Boolean) {
            do {
                when (val outputIndex = codec.dequeueOutputBuffer(info, if (waitForOutput) CODEC_TIMEOUT_US else 0L)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> return
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(outputTrack < 0) { "Audio encoder changed format twice." }
                        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                        outputTrack = muxer!!.addTrack(codec.outputFormat)
                        muxer!!.start()
                    }
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> if (outputIndex >= 0) {
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!isConfig && info.size > 0 &&
                            (durationUs <= 0 || info.presentationTimeUs <= durationUs + 100_000L)
                        ) {
                            val encoded = codec.getOutputBuffer(outputIndex)
                                ?: throw MediaIsolationException("مرمّز الصوت لم يُرجع بيانات.")
                            encoded.position(info.offset)
                            encoded.limit(info.offset + info.size)
                            muxer?.writeSampleData(outputTrack, encoded, info)
                        }
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (eos) endOfStream = true
                    }
                }
            } while (waitForOutput && !endOfStream)
        }

        override fun close() {
            runCatching { codec.stop() }
            codec.release()
            runCatching { muxer?.stop() }
            muxer?.release()
            muxer = null
        }
    }

    private object MediaCodecInfoCompat {
        const val AAC_OBJECT_LC = 2
    }

    private companion object {
        const val TARGET_SAMPLE_RATE = 16_000
        const val TARGET_FRAME_SAMPLES = 16_000
        const val AAC_MIME = "audio/mp4a-latm"
        const val AAC_BITRATE = 128_000
        const val CODEC_TIMEOUT_US = 10_000L
        const val NETWORK_TIMEOUT_MS = 30_000
        const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        const val MAX_DOWNLOAD_BYTES = 500L * 1024L * 1024L
        const val DEFAULT_SAMPLE_BUFFER_BYTES = 4 * 1024 * 1024
        const val USER_AGENT = "Halalify/1.0 (Android media isolation)"
        val UNSAFE_NAME = Regex("[^A-Za-z0-9._-]")
    }
}

private fun MediaFormat.intValue(key: String): Int =
    if (containsKey(key)) getInteger(key) else 0

private fun MediaFormat.longValue(key: String): Long =
    if (containsKey(key)) getLong(key) else 0L

private fun MediaFormat.stringValue(key: String): String =
    if (containsKey(key)) getString(key).orEmpty() else ""
