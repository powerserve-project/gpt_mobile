<div align="center">

<img width="200" height="200" style="display: block;" src="./images/logo.png">

</div>

# GPT Mobile + PowerServe

This subject is forked from open-source project [GPT Mobile](https://github.com/Taewan-P/gpt_mobile). The PowerServe is integrated into the app as one of the inference backend.

## Build

### Initialization 
This project is integrated with PowerServe as a submodule and build it as a JNI static library. 
Before building, please execute the following command(s) to **init submodules**:
```shell
git submodule update --init --recursive
```

### Prepare Libraries
This step is for QNN support. If you are to compile a executable for only-CPU platform, please skip this step.

Then copy QNN library into the directory `app/src/main/jniLibs/<arch>`. For latest phone "One plus 13" with architecture *arm64-v8a*, libraries should be copied into `app/src/main/jniLibs/arm64-v8a`, just like:
```
--- app
  --- src
    --- main
      --- jniLibs
        --- arm64-v8a
          --- libQnnHtp.so
          --- libQnnHtpV79.so
          --- libQnnHtpV79Skel.so
          --- libQnnHtpV79Stub.so
          --- libQnnSystem.so
```
where the 64-bit QNN libraries("libQnnHtp.so", "libQnnSystem.so", "libQnnHtpV79Stub.so") come from **"\$QNN_SDK_ROOT/lib/aarch64-android"** 
while the 32-bit QNN library("libQnnHtpV79.so", "libQnnHtpV79Skel.so") comes from **"\$QNN_SDK_ROOT/lib/hexagon-v79/unsigned"**

If you are using 8Gen3 or other qualcomm processors, the libraries placement is similar while the version should change to the proper one according to the [Documentation](https://docs.qualcomm.com/bundle/publicresource/topics/80-63442-50/overview.html#supported-snapdragon-devices):

| Processor  | Hexagon Arch |
|------------|--------------|
| 8Gen Elite | V79          |
| 8Gen 3     | V75          |


### Compile and Execute

> Do set the environment variable **QNN_SDK_ROOT** as the path to QNN SDK before compile this project when compiling an executable for NPU.
> 
> Do change the 

Open this project with Android Studio, download necessary dependencies automatically and build up the app.

## Usage

> Note: For reading and downloading models, the program acquire **MANAGE_EXTERNAL_STORAGE** privilege. PowerServe backend does not acquire network connection (except for model downloading), it will not collect any user data as well.

The program will search the model directory(/storage/emulated/0/Download/powerserve) in external storage for models. 
You can just select expected model for automatic downloading or download it from [huggingface](https://huggingface.co/PowerServe) to the model directory(/storage/emulated/0/Download/powerserve) directly.

Most of the time, you can find the Download directory(/storage/emulated/0/Download) in the file manager directly. For those who want to download and try a model manually, 
just create the directory `powerserve` under the Download directory and download models into it. The construction should be 
```shell
└── Download
    └── powerserve
        ├── SmallThinker-3B-PowerServe-QNN29-8Gen4
        │   ├── model.json
        │   ├── vocab.gguf
        │   ├── ggml
        │   │   └── weights.gguf
        │   └── qnn
        │       ├── lmhead.bin
        │       ├── kv
        │       │   └── ... 
        │       └── ...
        └──SmallThinker-0.5B-PowerServe-QNN29-8Gen4
            └── ...
```

## Features

- **Chat with multiple models at once**
  - Uses official APIs for each platforms
  - Supported platforms:
    - OpenAI GPT
    - Anthropic Claude
    - Google Gemini
    - Groq
    - Ollama
    - PowerServe
  - Can customize temperature, top p (Nucleus sampling), and system prompt
  - Custom API URLs, Custom Models are also supported
- Local chat history
  - Chat history is **only saved locally**
  - Only sends to official API servers while chatting
- [Material You](https://m3.material.io/) style UI, Icons
  - Supports dark mode, system dynamic theming **without Activity restart**
- Per app language setting for Android 13+
- 100% Kotlin, Jetpack Compose, Single Activity, [Modern App Architecture](https://developer.android.com/topic/architecture#modern-app-architecture) in Android developers documentation

## License

See [LICENSE](./LICENSE) for details.

[F-Droid Icon License](https://gitlab.com/fdroid/artwork/-/blob/master/fdroid-logo-2015/README.md)

## Known Issue

1. Because this app use PowerServe as local backend, which is in test mode currently, if some operation causes crash of the backend, the App crash as well.
2. PowerServe haven't implemented ring-round KVCache. When running multi-round chat or use o1-like model, it may incurs KVCache overflow.
3. Touching multiple model may fails to create QNN shared buffer if setting -DPOWERSERVE_SERVER_MULTIMODEL=ON in the build.gradle.kts.