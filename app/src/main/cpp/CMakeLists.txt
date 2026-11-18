cmake_minimum_required(VERSION 3.22.1)
project(diemobile C CXX)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CDIE_BUILD_GUI OFF CACHE BOOL "" FORCE)
set(CDIE_BUILD_CONSOLE OFF CACHE BOOL "" FORCE)
set(CDIE_BUILD_UPDATER OFF CACHE BOOL "" FORCE)
set(CDIE_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(CDIE_BUILD_LIBRARY ON CACHE BOOL "" FORCE)
set(DIE_BUILD_STATIC ON CACHE BOOL "" FORCE)
set(DIE_BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(CDIE_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../vendor/CDiE")
if(NOT EXISTS "${CDIE_ROOT}/CMakeLists.txt")
  message(FATAL_ERROR "Run scripts/fetch-engine.sh before configuring the native build")
endif()
add_subdirectory("${CDIE_ROOT}" cdie EXCLUDE_FROM_ALL)
set(ANALYSIS_SRC "${CDIE_ROOT}/src/app")
set(HASH_SRC "${CMAKE_CURRENT_SOURCE_DIR}/../../../../hash-engine")
add_library(diehash SHARED hash_bridge.c
  "${HASH_SRC}/hashes.c"
  "${HASH_SRC}/third_party/rhash/md4.c"
  "${HASH_SRC}/third_party/rhash/byte_order.c"
  "${CDIE_ROOT}/dep/xxfclib/src/algo/sha512/xx_sha512.c")
target_link_libraries(diehash PRIVATE die_static)
add_library(diemobile SHARED bridge.c tools_bridge.c desktop_bridge.c records_bridge.c structures_bridge.c
  "${CDIE_ROOT}/src/gui/gui_backend.c"
  "${CDIE_ROOT}/src/gui/gui_result_tree.c"
  "${ANALYSIS_SRC}/cdie_file_info.c"
  "${ANALYSIS_SRC}/cdie_memory_map.c"
  "${ANALYSIS_SRC}/cdie_imports.c"
  "${ANALYSIS_SRC}/cdie_exports.c"
  "${ANALYSIS_SRC}/cdie_symbols.c"
  "${ANALYSIS_SRC}/cdie_resources.c"
  "${ANALYSIS_SRC}/cdie_disasm.c"
  "${ANALYSIS_SRC}/cdie_hash.c"
  "${ANALYSIS_SRC}/cdie_entropy.c"
  "${ANALYSIS_SRC}/cdie_strings.c")
target_include_directories(diemobile PRIVATE "${CDIE_ROOT}/src")
# The unmodified RHash implementation uses aligned word reads for byte buffers.
set_source_files_properties("${HASH_SRC}/third_party/rhash/md4.c" PROPERTIES COMPILE_OPTIONS "-fno-strict-aliasing")
target_link_libraries(diemobile PRIVATE die_static)
if(ANDROID OR CMAKE_SYSTEM_NAME STREQUAL "Linux")
  target_link_options(diemobile PRIVATE "-Wl,--no-undefined")
  target_link_options(diehash PRIVATE "-Wl,--no-undefined")
endif()

# The same JNI adapter is exercised on Linux CI using known fixture binaries.
if(NOT ANDROID)
  find_package(JNI REQUIRED)
  target_include_directories(diemobile PRIVATE ${JNI_INCLUDE_DIRS})
  target_include_directories(diehash PRIVATE ${JNI_INCLUDE_DIRS})
endif()
