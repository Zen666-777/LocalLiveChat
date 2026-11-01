# Headers are newer than those bundled with NDK 27. Keep the Android Vulkan
# loader from the NDK, and use its host glslc to embed shaders in the APK.
# All downloaded source archives are pinned and SHA-256 verified.
include(FetchContent)
FetchContent_Declare(mel_vulkan_headers
    URL https://codeload.github.com/KhronosGroup/Vulkan-Headers/tar.gz/b5c8f996196ba4aa6d8f97e52b5d3b6e70f7e4e2
    URL_HASH SHA256=7937d8ec21785736436d9ff52564db7d55992070b5ac98324472cce6e425a7ba)
FetchContent_Declare(mel_vulkan_hpp
    URL https://codeload.github.com/KhronosGroup/Vulkan-Hpp/tar.gz/c27a9c9a96d50afe4a0fb88e7aa518c93817ab50
    URL_HASH SHA256=7b2cb2e7a8acd36206e80c5f8d97e34dc1d3aa9a027f83c62ca404f7210cf435)
FetchContent_Declare(mel_spirv_headers
    URL https://codeload.github.com/KhronosGroup/SPIRV-Headers/tar.gz/04f10f650d514df88b76d25e83db360142c7b174
    URL_HASH SHA256=1b220e3eec1714f0451b0e3652979bd280edf10893f617837b88e6359a804ded)
foreach(dependency mel_vulkan_headers mel_vulkan_hpp mel_spirv_headers)
    FetchContent_GetProperties(${dependency})
    if(NOT ${dependency}_POPULATED)
        FetchContent_Populate(${dependency})
    endif()
endforeach()
set(Vulkan_INCLUDE_DIR "${mel_vulkan_headers_SOURCE_DIR}/include" CACHE PATH "" FORCE)
# ggml's package discovery expects an installed config. This adapter points at
# the verified header-only source; nothing is fetched or installed at runtime.
set(SPIRV-Headers_DIR "${CMAKE_CURRENT_LIST_DIR}/spirv-headers" CACHE PATH "" FORCE)
if(ANDROID)
    if(CMAKE_HOST_WIN32)
        set(mel_shader_host windows-x86_64)
        set(mel_shader_suffix .exe)
    elseif(CMAKE_HOST_APPLE)
        set(mel_shader_host darwin-x86_64)
    else()
        set(mel_shader_host linux-x86_64)
    endif()
    set(Vulkan_GLSLC_EXECUTABLE "${ANDROID_NDK}/shader-tools/${mel_shader_host}/glslc${mel_shader_suffix}" CACHE FILEPATH "" FORCE)
    if(NOT EXISTS "${Vulkan_GLSLC_EXECUTABLE}")
        message(FATAL_ERROR "The Android NDK host glslc executable was not found")
    endif()
endif()
