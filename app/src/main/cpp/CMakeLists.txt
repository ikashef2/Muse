cmake_minimum_required(VERSION 3.22.1)
project(muse_fingerprint LANGUAGES C CXX)

include(FetchContent)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(FFT_LIB kissfft CACHE STRING "" FORCE)

FetchContent_Declare(
    chromaprint
    GIT_REPOSITORY https://github.com/acoustid/chromaprint.git
    GIT_TAG v1.6.1
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(chromaprint)

add_library(musefingerprint SHARED fingerprint_jni.cpp)
target_compile_features(musefingerprint PRIVATE cxx_std_17)
target_link_libraries(musefingerprint PRIVATE chromaprint log)
