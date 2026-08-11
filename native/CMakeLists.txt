cmake_minimum_required(VERSION 3.22.1)
project(halalify_vision LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(halalify_vision_core STATIC
        core/preprocess.cpp
        core/postprocess.cpp
        core/nms.cpp
        core/protection_policy.cpp)
target_include_directories(halalify_vision_core PUBLIC
        "${CMAKE_CURRENT_SOURCE_DIR}"
        "${CMAKE_CURRENT_SOURCE_DIR}/include")
if(MSVC)
    target_compile_options(halalify_vision_core PRIVATE /W4)
else()
    target_compile_options(halalify_vision_core PRIVATE -Wall -Wextra -Wpedantic)
endif()

if(ANDROID)
    if(NOT DEFINED TFLITE_NATIVE_DIR)
        message(FATAL_ERROR "TFLITE_NATIVE_DIR must point to the extracted LiteRT AAR.")
    endif()
    set(TFLITE_MODULE_DIR
            "${TFLITE_NATIVE_DIR}/prefab/modules/tensorflowlite_jni_gms_client")
    set(TFLITE_LIBRARY
            "${TFLITE_MODULE_DIR}/libs/android.${ANDROID_ABI}/libtensorflowlite_jni_gms_client.so")
    if(NOT EXISTS "${TFLITE_LIBRARY}")
        message(FATAL_ERROR "LiteRT native library was not extracted for ${ANDROID_ABI}.")
    endif()
    add_library(tensorflowlite_jni_gms_client SHARED IMPORTED)
    set_target_properties(tensorflowlite_jni_gms_client PROPERTIES
            IMPORTED_LOCATION "${TFLITE_LIBRARY}"
            INTERFACE_INCLUDE_DIRECTORIES "${TFLITE_MODULE_DIR}/include")

    add_library(halalify_litert_backend STATIC
            backends/litert_backend.cpp
            vision_engine.cpp)
    target_include_directories(halalify_litert_backend PUBLIC
            "${CMAKE_CURRENT_SOURCE_DIR}"
            "${CMAKE_CURRENT_SOURCE_DIR}/include")
    target_compile_definitions(halalify_litert_backend PRIVATE
            TFLITE_IN_GMSCORE
            TFLITE_WITH_STABLE_ABI)
    target_compile_options(halalify_litert_backend PRIVATE -Wall -Wextra -Wpedantic)
    target_link_libraries(halalify_litert_backend
            PUBLIC halalify_vision_core
            PRIVATE tensorflowlite_jni_gms_client)

    add_library(halalify_android_jni SHARED
            platform/android/android_jni.cpp
            platform/android/asset_model_loader.cpp)
    target_include_directories(halalify_android_jni PRIVATE
            "${CMAKE_CURRENT_SOURCE_DIR}"
            "${CMAKE_CURRENT_SOURCE_DIR}/include")
    target_compile_options(halalify_android_jni PRIVATE -Wall -Wextra -Wpedantic)
    target_link_libraries(halalify_android_jni
            PRIVATE halalify_litert_backend android log)
else()
    option(HB_BUILD_TESTS "Build Halalify C++ host tests" ON)
    if(HB_BUILD_TESTS)
        enable_testing()
        add_executable(halalify_host_tests tests/core_test.cpp)
        target_link_libraries(halalify_host_tests PRIVATE halalify_vision_core)
        add_test(NAME halalify_host_tests COMMAND halalify_host_tests)
    endif()
endif()
