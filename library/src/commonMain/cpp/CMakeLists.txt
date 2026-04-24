cmake_minimum_required(VERSION 3.5)
project(llama_jni)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# DO NOT force CMAKE_BUILD_TYPE here; Android/Gradle controls it.

# Path to vendored llama.cpp inside the repo
set(LLAMA_CPP_DIR "${CMAKE_SOURCE_DIR}/../../../../llama.cpp")
set(MTMD_DIR "${LLAMA_CPP_DIR}/tools/mtmd")

# Build llama.cpp "common" helpers (needed for json-schema-to-grammar)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "Build llama.cpp common helpers" FORCE)

# Disable curl on Android builds (NDK doesn't provide libcurl by default)
set(LLAMA_CURL OFF CACHE BOOL "Disable curl support" FORCE)

# Also disable things that commonly pull in extra deps
set(LLAMA_SERVER OFF CACHE BOOL "Disable server" FORCE)
set(LLAMA_EXAMPLES OFF CACHE BOOL "Disable examples" FORCE)
set(LLAMA_TESTS OFF CACHE BOOL "Disable tests" FORCE)
set(LLAMA_BENCHMARK OFF CACHE BOOL "Disable benchmarks" FORCE)

if(ANDROID)
    message(STATUS "Configuring llama.cpp for Android ABI: ${ANDROID_ABI}")
    if(ANDROID_ABI STREQUAL "armeabi-v7a")
        set(GGML_LLAMAFILE OFF CACHE BOOL "Disable GGML llamafile on armeabi-v7a" FORCE)
        set(GGML_USE_LLAMAFILE OFF CACHE BOOL "" FORCE)
    endif()

    # ── Vulkan GPU backend (Tensor G4 / Arm Immortalis-G715 supports Vulkan 1.3) ──
    # Only enable on arm64-v8a: Tensor G4 is 64-bit ARM; armeabi-v7a / x86 are
    # emulator/legacy targets where Vulkan 1.1 is unavailable and unnecessary.
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_VULKAN ON CACHE BOOL "Enable Vulkan compute backend" FORCE)
    else()
        set(GGML_VULKAN OFF CACHE BOOL "Vulkan disabled for non-arm64 ABIs" FORCE)
    endif()

    # glslc (SPIR-V shader compiler):
    #   NDK's glslc is a dynamically-linked ELF that can't run on NixOS (wrong
    #   interpreter path). Use the Nix-packaged shaderc glslc instead.
    set(Vulkan_GLSLC_EXECUTABLE
        "/nix/store/p4xzrnhzxc1kaplbwj2rjlpp4zzysxqn-shaderc-2025.3-bin/bin/glslc"
        CACHE FILEPATH "Nix-packaged glslc (shaderc) for SPIR-V shader compilation" FORCE)

    # Vulkan-Hpp: NDK only ships vulkan.h (C header), not vulkan.hpp.
    # FindVulkan.cmake does path-string concatenation on Vulkan_INCLUDE_DIR
    # (e.g. ${Vulkan_INCLUDE_DIR}/vulkan/vulkan_core.h), so it must be a single dir.
    set(Vulkan_INCLUDE_DIR
        "/nix/store/4555d6l3mc79fnbcp5sckgjh30agg6sd-vulkan-headers-1.4.328.0/include"
        CACHE PATH "Nix vulkan-headers include dir (provides vulkan/vulkan.hpp)" FORCE)

    # SPIRV headers: ggml-vulkan.cpp also needs:
    #   <spirv/unified1/spirv.hpp>  → spirv-headers-1.4.328.0/include
    #   <spirv-headers/spirv.hpp>   → vulkan-compat/ symlink
    # Add globally — include_directories() propagates into subdirectory targets.
    include_directories(SYSTEM
        "/nix/store/902ycglkcwhvsas14midqqw3n2c67774-spirv-headers-1.4.328.0/include"
        "${CMAKE_SOURCE_DIR}/vulkan-compat"
    )

    # libvulkan.so is a system loader on Android — link via find_library.
    find_library(Vulkan_LIBRARY vulkan)
    if(Vulkan_LIBRARY)
        set(Vulkan_LIBRARIES "${Vulkan_LIBRARY}" CACHE STRING "" FORCE)
        set(Vulkan_FOUND TRUE CACHE BOOL "" FORCE)
        message(STATUS "Vulkan backend enabled. glslc=${Vulkan_GLSLC_EXECUTABLE}")
    else()
        message(WARNING "libvulkan.so not found — disabling Vulkan backend")
        set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
    endif()

    # ── vulkan-shaders-gen host compiler ─────────────────────────────────────────
    # ggml-vulkan builds a native (host-arch) binary called vulkan-shaders-gen using
    # a generated host-toolchain.cmake. On NixOS the NDK's clang (the host-detected
    # compiler) lacks the NixOS sysroot, so it can't link Scrt1.o / libc.
    # Pre-set HOST_C_COMPILER / HOST_CXX_COMPILER to the NixOS gcc-wrapper which has
    # the NixOS library paths baked in. detect_host_compiler() skips find_program()
    # when these cache variables are already populated.
    set(HOST_C_COMPILER
        "/nix/store/kilyfvfmh9x9rlaj47ph1916lbc1pnsr-gcc-wrapper-14.3.0/bin/gcc"
        CACHE FILEPATH "NixOS host C compiler for vulkan-shaders-gen" FORCE)
    set(HOST_CXX_COMPILER
        "/nix/store/kilyfvfmh9x9rlaj47ph1916lbc1pnsr-gcc-wrapper-14.3.0/bin/g++"
        CACHE FILEPATH "NixOS host C++ compiler for vulkan-shaders-gen" FORCE)
endif()

# Add llama.cpp as a subdirectory (builds ggml, llama, common, etc)
add_subdirectory("${LLAMA_CPP_DIR}" "${CMAKE_BINARY_DIR}/llama")

set(WHISPER_CPP_DIR "${CMAKE_SOURCE_DIR}/../../../../whisper.cpp")

# whisper.cpp options
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)

add_subdirectory("${WHISPER_CPP_DIR}" "${CMAKE_BINARY_DIR}/whisper")

# stable-diffusion.cpp (repo-root submodule)
set(STABLE_DIFFUSION_CPP_DIR "${CMAKE_SOURCE_DIR}/../../../../stable-diffusion.cpp")
set(SD_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(SD_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_GGML_LIB OFF CACHE BOOL "" FORCE)
set(SD_USE_SYSTEM_GGML OFF CACHE BOOL "" FORCE)

add_subdirectory("${STABLE_DIFFUSION_CPP_DIR}" "${CMAKE_BINARY_DIR}/stable-diffusion")

find_library(log-lib log)

# Build mtmd (multimodal) sources as an object library to include in llama_jni
add_library(mtmd_sources OBJECT
        ${MTMD_DIR}/mtmd.cpp
        ${MTMD_DIR}/mtmd-helper.cpp
        ${MTMD_DIR}/mtmd-audio.cpp
        ${MTMD_DIR}/mtmd-image.cpp
        ${MTMD_DIR}/clip.cpp
        ${MTMD_DIR}/models/cogvlm.cpp
        ${MTMD_DIR}/models/conformer.cpp
        ${MTMD_DIR}/models/deepseekocr.cpp
        ${MTMD_DIR}/models/dotsocr.cpp
        ${MTMD_DIR}/models/gemma4a.cpp
        ${MTMD_DIR}/models/gemma4v.cpp
        ${MTMD_DIR}/models/glm4v.cpp
        ${MTMD_DIR}/models/hunyuanocr.cpp
        ${MTMD_DIR}/models/internvl.cpp
        ${MTMD_DIR}/models/kimik25.cpp
        ${MTMD_DIR}/models/kimivl.cpp
        ${MTMD_DIR}/models/llama4.cpp
        ${MTMD_DIR}/models/llava.cpp
        ${MTMD_DIR}/models/minicpmv.cpp
        ${MTMD_DIR}/models/mobilenetv5.cpp
        ${MTMD_DIR}/models/nemotron-v2-vl.cpp
        ${MTMD_DIR}/models/paddleocr.cpp
        ${MTMD_DIR}/models/pixtral.cpp
        ${MTMD_DIR}/models/qwen2vl.cpp
        ${MTMD_DIR}/models/qwen3a.cpp
        ${MTMD_DIR}/models/qwen3vl.cpp
        ${MTMD_DIR}/models/siglip.cpp
        ${MTMD_DIR}/models/step3vl.cpp
        ${MTMD_DIR}/models/whisper-enc.cpp
        ${MTMD_DIR}/models/youtuvl.cpp
        ${MTMD_DIR}/deprecation-warning.cpp
)

target_include_directories(mtmd_sources PRIVATE
        ${MTMD_DIR}
        ${LLAMA_CPP_DIR}/include
        ${LLAMA_CPP_DIR}/src
        ${LLAMA_CPP_DIR}/ggml/include
        ${LLAMA_CPP_DIR}/ggml/src
        ${LLAMA_CPP_DIR}/common
        ${LLAMA_CPP_DIR}/vendor
        ${LLAMA_CPP_DIR}/vendor/stb
        ${LLAMA_CPP_DIR}/vendor/nlohmann
)

target_compile_options(mtmd_sources PRIVATE -Wno-cast-qual)
target_compile_features(mtmd_sources PRIVATE cxx_std_17)

add_library(
        llama_jni
        SHARED
        llama_jni.cpp
        llama_embed.cpp
        whisper_stt.cpp
        whisper_jni.cpp
        stable_diffusion_jni.cpp
        multimodal_jni.cpp
        $<TARGET_OBJECTS:mtmd_sources>
)

target_include_directories(
        llama_jni
        PRIVATE
        ${CMAKE_SOURCE_DIR}/../c_interop/include
        ${LLAMA_CPP_DIR}/include
        ${LLAMA_CPP_DIR}/ggml/include
        ${LLAMA_CPP_DIR}/ggml/src
        ${LLAMA_CPP_DIR}/common
        ${MTMD_DIR}
        # nlohmann headers live under llama.cpp/vendor in many revisions
        ${LLAMA_CPP_DIR}/vendor
        ${LLAMA_CPP_DIR}/vendor/nlohmann
        ${LLAMA_CPP_DIR}/vendor/stb
        ${WHISPER_CPP_DIR}
        ${WHISPER_CPP_DIR}/include
        ${STABLE_DIFFUSION_CPP_DIR}/include
)

target_link_libraries(
        llama_jni
        PRIVATE
        llama
        common
        whisper
        stable-diffusion
        ${log-lib}
)
