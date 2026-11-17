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
add_library(diemobile SHARED bridge.c tools_bridge.c desktop_bridge.c
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
target_link_libraries(diemobile PRIVATE die_static)

# The same JNI adapter is exercised on Linux CI using known fixture binaries.
if(NOT ANDROID)
  find_package(JNI REQUIRED)
  target_include_directories(diemobile PRIVATE ${JNI_INCLUDE_DIRS})
endif()
