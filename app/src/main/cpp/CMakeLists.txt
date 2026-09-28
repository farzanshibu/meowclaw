cmake_minimum_required(VERSION 3.22.1)
project(meowclaw_needle CXX)

add_library(meowclaw_needle SHARED needle_jni.cpp)
target_compile_features(meowclaw_needle PRIVATE cxx_std_17)
target_include_directories(meowclaw_needle PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(meowclaw_needle PRIVATE android log m)

# Needle 3 ships prebuilt engines for ARM only. Other ABIs (the x86_64
# emulator) build a stub that reports the engine as unavailable.
set(NEEDLE_STATIC "${NEEDLE_LIB_DIR}/${ANDROID_ABI}/libneedle.a")
if(EXISTS "${NEEDLE_STATIC}")
    add_library(needle STATIC IMPORTED)
    set_target_properties(needle PROPERTIES IMPORTED_LOCATION "${NEEDLE_STATIC}")
    target_link_libraries(meowclaw_needle PRIVATE needle)
    target_compile_definitions(meowclaw_needle PRIVATE NEEDLE_AVAILABLE=1)
    if(ANDROID_ABI STREQUAL "armeabi-v7a")
        target_sources(meowclaw_needle PRIVATE libcxx_compat.cpp)
    endif()
endif()

target_link_options(meowclaw_needle PRIVATE "-Wl,-z,max-page-size=16384")
