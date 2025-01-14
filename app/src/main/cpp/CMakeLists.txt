cmake_minimum_required(VERSION 3.20)

project("powerserve_java")

set(CMAKE_CXX_STANDARD 20)

add_subdirectory(PowerServe)

add_library(${CMAKE_PROJECT_NAME} SHARED
    # List C/C++ source files with relative paths to this CMakeLists.txt.
    powerserve.cpp)

# Specifies libraries CMake should link to your target library. You
# can link libraries from various origins, such as libraries defined in this
# build script, prebuilt third-party libraries, or Android system libraries.
target_link_libraries(${CMAKE_PROJECT_NAME}
    # List libraries link to the target library
    android
    log

    server.local
    nlohmann_json
)