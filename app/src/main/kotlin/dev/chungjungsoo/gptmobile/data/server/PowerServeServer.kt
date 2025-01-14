package dev.chungjungsoo.gptmobile.data.server
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionChunk
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.core.RequestOptions
import com.aallam.openai.client.Chat
import dev.chungjungsoo.gptmobile.BuildConfig
import dev.chungjungsoo.gptmobile.R
import kotlin.io.path.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.isActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ErrorMessage(val code: Int, val message: String, val type: String)

const val DEFAULT_HPARAMS_JSON = """	
{
    "n_threads": 4,
    "batch_size": 1024,
    "sampler": {
        "seed": 233,
        "temperature": 0.2,
        "top_p": 0.9,
        "top_k": 40,
        "min_keep": 0,
        "penalty_last_n": 1024,
        "penalty_repeat": 1.1,
        "penalty_freq": 0,
        "penalty_present": 0,
        "penalize_nl": false,
        "ignore_eos": false
    }
}
"""

const val DEFAULT_WORKSPACE_JSON = """
{
    "executables": "",
    "hparams_config": "hparams.json",
    "model_main": "",
    "model_draft": ""
}
"""


const val STREAM_PREFIX    = "data:"

const val STREAM_END_TOKEN = "$STREAM_PREFIX [DONE]"

fun wrapModelName(modelName: String): String {
    return modelName.split("+").joinToString(separator = "+") { subModelName ->
        "$subModelName-PowerServe-QNN${BuildConfig.QNN_SDK_VERSION}-${BuildConfig.HEXAGON_VERSION}"
    }
}

fun dewrapModelName(modelName: String): String {
    return modelName.split("+").joinToString(separator = "+") { subModelName ->
        subModelName.removeSuffix("-PowerServe-QNN${BuildConfig.QNN_SDK_VERSION}-${BuildConfig.HEXAGON_VERSION}")
    }
}

class PowerServeHolder(modelFolder: String, nativeLibPath: String) : AutoCloseable {
    private var nativePtr: Long = 0L

    init {
        nativePtr = create_power_serve(modelFolder, nativeLibPath)
        if (nativePtr == 0L) {
            throw RuntimeException("failed to create PowerServe object")
        }
    }


    override fun close() {
        if (nativePtr != 0L) {
            destroy_power_serve(nativePtr)
            nativePtr = 0L
        }
    }

    protected fun finalize() {
        close()
    }

    fun setupChatTask(request: String): Long {
        val ret =  power_serve_chat_completion(nativePtr, request)
        if (ret == 0L) {
            throw RuntimeException("failed to create PowerServe chat task")
        }
        return ret
    }

    fun tryFetchResultChunk(responsePtr: Long): String? {
        val result = power_serve_try_fetch_result(nativePtr, responsePtr)
        return result
    }

    fun destroyChatTask(responsePtr: Long) {
        power_serve_destroy_response(nativePtr, responsePtr)
    }

    private external fun create_power_serve(modelFolder: String, nativeLibPath: String): Long

    private external fun destroy_power_serve(ptr: Long)

    private external fun power_serve_chat_completion(ptr: Long, request: String): Long

    private external fun power_serve_destroy_response(serverPtr: Long, responsePtr: Long)

    private external fun power_serve_try_fetch_result(serverPtr: Long, responsePtr: Long): String?

    companion object {
        init {
            System.loadLibrary("powerserve_java")
        }
    }
}

class PowerServeDownloader(appContext: Context) {
    val context               = appContext

    private val downloadDirectory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path
    val externalDirectory = Path(downloadDirectory)/"powerserve"

    private var stopReason = "None"

    @Volatile
    var onGoing = false

    suspend fun download(modelName: String): String {
        val modelId   = dewrapModelName(modelName)
        val repoPath  = "PowerServe/$modelName"
        val modelPath = externalDirectory/modelName

        try {
            var startTime = System.currentTimeMillis()
            var prevPercentage = 0
            val modelDownloader = ModelDownloader(context, modelPath.toString())
            modelDownloader.downloadDirectory(repoPath).catch {
                stopReason = "Failed to download ${modelId}: ${it.message.toString()}"
            }.onCompletion {
                stopReason = "Finish downloading $modelId"
            }.collect { progress ->
                val endTime = System.currentTimeMillis()
                if (progress.percentage > prevPercentage + 5 || endTime - startTime > 2_000) {
                    val speed = if (progress.speedKBps > 1000) "${progress.speedKBps / 1000}MB/s" else "${progress.speedKBps}KB/s"
                    Toast.makeText(context, "Download $modelId: ${progress.percentage}% (${speed})", Toast.LENGTH_SHORT).show()
                    prevPercentage = progress.percentage
                    startTime = endTime
                }
            }
        } catch (err: Exception) {
            onGoing = false
            throw err
        }

        onGoing = false
        Toast.makeText(context, stopReason, Toast.LENGTH_LONG).show()
        return modelPath.toString()
    }

    fun findModel(modelName: String): String? {
        val downloadModel = externalDirectory/modelName

        if (downloadModel.exists() && downloadModel.isDirectory()) {
            return downloadModel.toString()
        }
        return null
    }

    fun validateModel(modelName: String): Boolean {
        val modelPath = externalDirectory/modelName
        val modelDownloader = ModelDownloader(context, modelPath.toString())

        val fileList = modelDownloader.fetchLocalModelFileList(modelName)
        fileList.forEach { file ->
            if ((modelPath/file).notExists()) {
                Log.w("PowerServeDownloader", "Failed to validate model due to the file $file not found")
                return false
            }
        }
        Log.w("PowerServeDownloader", "Failed to get file list of model $modelName")
        return true
    }
}

class PowerServeServer(appContext: Context): Chat {
    private val context               = appContext
    private val powerServeDownloader  = PowerServeDownloader(context)
    private var powerServeHolder      = PowerServeHolder(powerServeDownloader.externalDirectory.toString(), context.applicationInfo.nativeLibraryDir)

    private fun escapeString(string: String): String {
        val escapedMessageStringBuilder = StringBuilder()
        for (c in string) {
            when {
                c == '\n' -> escapedMessageStringBuilder.append("\\n")
                c == '\t' -> escapedMessageStringBuilder.append("\\t")
                c == '\r' -> escapedMessageStringBuilder.append("\\r")
                c == '\"' -> escapedMessageStringBuilder.append("\\\"")
                c.code < 32 -> escapedMessageStringBuilder.append(String.format("\\u%04x", c.code))
                else -> escapedMessageStringBuilder.append(c)
            }
        }
        return escapedMessageStringBuilder.toString()
    }

    private fun requestToJsonString(request: ChatCompletionRequest, overwriteModelPath: String?): String {
        val messages = request.messages
        val messagesString = messages.joinToString(separator = ",") { message ->
            """
                { "role" : "${message.role.role}", "content": "${message.content?.let { escapeString(it) }}" }
            """.trimIndent()
        }

        var options = ""
        if (request.maxTokens != null) { options += """ "max_tokens": ${request.maxTokens}, """ }
        if (request.topP != null) { options += """ "top_p": ${request.topP}, """ }
        if (request.presencePenalty != null) { options += """ "presence_penalty": ${request.presencePenalty}, """ }
        if (request.frequencyPenalty != null) { options += """ "frequency_penalty": ${request.frequencyPenalty}, """ }

        val requestJson = """
            {
                "model": "${overwriteModelPath ?: request.model.id}",
                $options
                "messages": [
                    $messagesString
                ],
                "stream": true
            }
        """.trimIndent()
        return requestJson
    }

    private fun permissionAcquire() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!Environment.isExternalStorageManager()) {
                Toast.makeText(context, R.string.powerserve_storage_permission, Toast.LENGTH_LONG).show()

                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.setData(Uri.parse("package:" + context.packageName));
                context.startActivity(intent)
                throw Exception("Failed to get external storage permission for model access")
            }
        }
    }

    private fun initConfiguration() {
        val externalDirectory = powerServeDownloader.externalDirectory
        externalDirectory.absolute().toFile().mkdirs()

        val hparamsJsonPath   = externalDirectory/"hparams.json"
        val hparamsFile       = hparamsJsonPath.toFile()
        if (!hparamsFile.exists()) {
            Log.i("PowerServeServer", "$hparamsJsonPath not found, create one.")
            hparamsFile.writeText(DEFAULT_HPARAMS_JSON)
        }

        val workspaceJsonPath = externalDirectory/"workspace.json"
        val workspaceFile     = workspaceJsonPath.toFile()
        if (!workspaceFile.exists()) {
            Log.i("PowerServeServer", "$workspaceJsonPath not found, create one.")
            workspaceJsonPath.toFile().writeText(DEFAULT_WORKSPACE_JSON)
        }
    }

    private suspend fun downloadModel(modelName: String): String {
        Toast.makeText(context, "Failed to find model $modelName, trying to download automatically", Toast.LENGTH_SHORT).show()

        val modelPath = powerServeDownloader.download(modelName)
        Log.i("PowerServeServer", "Download mode $modelName successfully in $modelPath")
        return modelPath
    }

    private suspend fun fetchModel(modelName: String): String {
        return modelName.split("+").map { subModelName ->
            var modelPath = powerServeDownloader.findModel(subModelName)
            if (modelPath == null) {
                Log.d("PowerServeServer", "Failed to find model $subModelName, trying to download automatically")
                modelPath = downloadModel(subModelName)
            } else if (!powerServeDownloader.validateModel(subModelName)) {
                Log.w("PowerServeServer", "Failed to validate model files, removing it and trying to download automatically")
                modelPath = downloadModel(subModelName)
            } else {
                Log.i("PowerServeServer", "Found model $subModelName in $modelPath")
            }
            modelPath
        }.joinToString(separator = "+")
    }

    override suspend fun chatCompletion(request: ChatCompletionRequest, requestOptions: RequestOptions?): ChatCompletion {
        permissionAcquire()
        initConfiguration()

        val modelName = wrapModelName(request.model.id)
        val modelPath = fetchModel(modelName)

        val requestJson = requestToJsonString(request, modelPath)
        val responsePtr = powerServeHolder.setupChatTask(requestJson)
        val responseJson = powerServeHolder.tryFetchResultChunk(responsePtr)
        powerServeHolder.destroyChatTask(responsePtr)
        val withUnknownKeysJson = Json { ignoreUnknownKeys = true }
        return responseJson?.let { withUnknownKeysJson.decodeFromString<ChatCompletion>(it) } ?: Json.decodeFromString("")
    }

    private suspend inline fun  <reified T> FlowCollector<T>.chatCompletionFlowImpl(responsePtr: Long)  {
        val withUnknownKeysJson = Json {
            isLenient = true
            ignoreUnknownKeys = true
        }

        while (currentCoroutineContext().isActive) {
            delay(50) // switch to other context (e.g. UI)

            val responseChunk = powerServeHolder.tryFetchResultChunk(responsePtr)?: ""
            if (responseChunk.isEmpty()) { continue }

            Log.d("PowerServeServer", "Fetch response $responseChunk")
            if (responseChunk.startsWith(STREAM_END_TOKEN)) { break }
            else if (responseChunk.startsWith(STREAM_PREFIX)) {
                try {
                    val resultChunk = withUnknownKeysJson.decodeFromString<T>(responseChunk.removePrefix(STREAM_PREFIX))
                    emit(resultChunk)
                } catch(err: Exception) {
                    Log.i("PowerServeServer", "Chat Completion complete with response $responseChunk (${responseChunk.startsWith(STREAM_END_TOKEN)}) exception: $err")
                }
            } else {
                try {
                    val error = withUnknownKeysJson.decodeFromString<ErrorMessage>(responseChunk)
                    throw Exception("Receive error[${error.type}]: ${error.message}")
                } catch (err: IllegalArgumentException) {
                    Log.e("PowerServeServer", "Unknown response: $responseChunk")
                }
            }
        }
    }

    override fun chatCompletions(request: ChatCompletionRequest, requestOptions: RequestOptions?): Flow<ChatCompletionChunk> {
        return flow {
            if (powerServeDownloader.onGoing) {
                throw Exception("There is an model on downloading. Please wait...")
            }

            permissionAcquire()
            initConfiguration()

            val modelName = wrapModelName(request.model.id)
            val modelPath = fetchModel(modelName)

            delay(20)
            val requestJson = requestToJsonString(request, modelPath)
            Log.d("PowerServeServer", requestJson)
            val responsePtr = powerServeHolder.setupChatTask(requestJson)

            chatCompletionFlowImpl(responsePtr)
            powerServeHolder.destroyChatTask(responsePtr)
        }
    }
}
