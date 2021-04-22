cmake_minimum_required(VERSION 3.16)
project(lincheck_test)

set(CMAKE_CXX_STANDARD 20)

add_executable(lincheck_test main.cpp lincheck.h)

add_library(native SHARED IMPORTED GLOBAL)
set_target_properties(native PROPERTIES IMPORTED_LOCATION "${PROJECT_SOURCE_DIR}/../build/bin/native/debugShared/libnative.so")
target_link_libraries(lincheck_test native)
