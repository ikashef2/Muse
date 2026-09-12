cmake_minimum_required(VERSION 3.22.1)
project(muse_fingerprint LANGUAGES C CXX)

include(FetchContent)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(FFT_LIB kissfft CACHE STRING "" FORCE)

# Chromaprint's FindKissFFT module expects the embedding project to provide
# the KissFFT source directory. Fetch a stable copy for reproducible Android builds.
FetchContent_Declare(
    kissfft
    GIT_REPOSITORY https://github.com/mborgerding/kissfft.git
    GIT_TAG 131.1.0
    GIT_SHALLOW TRUE
)
FetchContent_GetProperties(kissfft)
if(NOT kissfft_POPULATED)
    FetchContent_Populate(kissfft)
endif()
set(KISSFFT_SOURCE_DIR "${kissfft_SOURCE_DIR}" CACHE PATH "" FORCE)

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
