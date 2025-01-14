package dev.chungjungsoo.gptmobile.data.server

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.io.path.div
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

data class DownloadProgress(val percentage: Int, val speedKBps: Long)

@Serializable
data class HuggingFaceModelIndex(val type: String, val size: Long, val path: String)

class ModelDownloader(
        private val appContext: Context,
        private val outputDirectory: String
    ) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonParser = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    fun fetchLocalModelFileList(modelName: String): List<String> {
        // TODO: fetch information about model dynamically through http
        return listOf("model.json", "vocab.gguf", "ggml/weights.gguf", "qnn/config.json")
    }

    private fun fetchHuggingFaceIndex(baseUrl: String): MutableList<HuggingFaceModelIndex> {
        val request = Request.Builder().url(baseUrl).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                response.close()
                throw Exception("Failed to fetch model index from $baseUrl : ${response.code}")
            }

            val responseBody = response.body?.string() ?: ""
            if (response.body == null) {
                response.close()
                throw Exception( "Empty response body from $baseUrl : ${response.code}")
            }

            response.close()
            val fileList = jsonParser.decodeFromString<MutableList<HuggingFaceModelIndex>>(responseBody)
            return fileList
        }
    }

    private fun fetchHuggingFaceModelIndex(baseUrl: String): Flow<List<String>> = flow {
        try {
            Log.i("ModelDownloader", "Fetch model index from $baseUrl")
            val resHuggingFaceItemArray = fetchHuggingFaceIndex(baseUrl)
            emit(resHuggingFaceItemArray.filter { it.type == "file" }.map { it.path })

            delay(50)

            var curItemIdx = 0
            while (curItemIdx < resHuggingFaceItemArray.size) {
                val curItem = resHuggingFaceItemArray[curItemIdx]
                if (curItem.type == "directory") {
                    val curUrl = "$baseUrl/${curItem.path}"
                    Log.i("ModelDownloader", "Fetch model index from $curUrl")
                    val newItemArray = fetchHuggingFaceIndex(curUrl)
                    emit(newItemArray.filter { it.type == "file" }.map { it.path })
                    resHuggingFaceItemArray.addAll(newItemArray)

                    delay(50)
                }

                ++curItemIdx
            }
        } catch (err: Exception) {
            err.message?.let { Log.e("ModelDownloader", it) }
            throw err
        }
    }.flowOn(Dispatchers.IO)

    fun downloadDirectory(modelId: String): Flow<DownloadProgress> = flow {
        val originBaseUrl = "https://huggingface.co"
        val mirrorBaseUrl = "https://hf-mirror.com"

        var fail = false

        for (selectedBase in arrayOf(originBaseUrl, mirrorBaseUrl)) {
            val baseUrl   = "$selectedBase/api/models/$modelId/tree/main"
            val resolveUrl = "$selectedBase/$modelId/resolve/main"

            Log.i("ModelDownloader", "Trying to fetch $modelId from $selectedBase")

            // Create output directory if not existed
            File(outputDirectory).mkdirs()

            // Download file index
            val fileList = mutableListOf<String>()

            fail = false

            try {
                fetchHuggingFaceModelIndex(baseUrl).collect{ itemList  ->
                    fileList += itemList
                    delay(20)
                }
            } catch (err: Exception) {
                err.printStackTrace()
                fail = true
            }

            if (fail) { continue }

            val jobList  = mutableListOf<Job>()
            val sizeList = MutableList<Long>(fileList.size) { _ -> 0 }
            val downList = MutableList<Long>(fileList.size) { _ -> 0 }

            try {
                fileList.forEachIndexed { index, fileName ->
                    val dstPath = Path(outputDirectory) / fileName
                    val dstDir = dstPath.parent
                    dstDir.toFile().mkdirs()

                    val url = "$resolveUrl/$fileName"
                    Log.i("ModelDownloader", "Download $fileName into $dstDir from $url")

                    if (fileName != ".gitattributes" && fileName != "README.md") {
                        val request = Request.Builder().url(url).build()

                        val job = CoroutineScope(Dispatchers.IO).launch {
                            client.newCall(request).execute().use { response ->
                                if (!response.isSuccessful) {
                                    response.close()
                                    throw Exception("Failed to fetch model index from $url : ${response.code}")
                                }

                                val fileSize = response.body?.contentLength()
                                sizeList[index] = fileSize?: 0

                                response.body?.byteStream()?.use { inputStream ->
                                    dstPath.toFile().outputStream().use { outputStream ->
                                        val buffer = ByteArray(8192)
                                        var byteRead: Int
                                        var totalByte = 0L

                                        while (inputStream.read(buffer).also { byteRead = it } != -1) {
                                            outputStream.write(buffer, 0, byteRead)
                                            totalByte += byteRead
                                            downList[index]  = totalByte
                                        }
                                        Log.i("ModelDownloader", "Finished downloading file $fileName of $totalByte bytes")
                                    }
                                }

                                response.close()
                            }
                        }

                        job.start()
                        jobList.add(job)
                    }
                }

                var prevDownSize = 0L
                var startTime    = System.currentTimeMillis()

                jobList.forEachIndexed{ index, job ->
                    while (!job.isCompleted && !job.isCancelled) {
                        val endTime   = System.currentTimeMillis()
                        val totalSize = max(sizeList.sum() , 1) // avoid dividing zero
                        val downSize  = downList.sum()
                        val duration  = max(endTime - startTime, 1) // avoid dividing zero
                        Log.i("ModelDownloader", "Downloaded file ${index}/${jobList.size}")
                        emit(DownloadProgress(
                            percentage = (downSize * 100 / totalSize).toInt(),
                            speedKBps = (downSize - prevDownSize) / duration
                        ))

                        prevDownSize = downSize
                        startTime    = endTime
                        delay(200)
                    }
                    if (job.isCancelled) {
                        throw Exception()
                    }
                }
            } catch (err: Exception) {
                Log.e("ModelDownloader", "Failed to download $modelId from $selectedBase: ${err.message}")
                jobList.forEachIndexed { index, job ->
                    if (job.isActive) {
                        job.cancel()
                    } else {
                        val fileName = fileList[index]
                        val filePath = Path(outputDirectory) / fileName
                        filePath.toFile().delete()
                    }
                }

                fail = true
            }

            if (!fail) { break } // Use another url for index downloading
        }

        if (fail) {
            throw Exception("Failed to download $modelId")
        }
    }
}
