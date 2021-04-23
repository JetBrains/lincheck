cmake_minimum_required(VERSION 3.16)
project(lincheck_test)

set(CMAKE_CXX_STANDARD 20)

# add lincheck shared library
add_library(lincheck_library SHARED IMPORTED GLOBAL)
set_target_properties(lincheck_library PROPERTIES IMPORTED_LOCATION "${PROJECT_SOURCE_DIR}/../build/bin/native/releaseShared/libnative.so")

include(FetchContent)
FetchContent_Declare(
        googletest
        URL https://github.com/google/googletest/archive/609281088cfefc76f9d0ce82e1ff6c30cc3591e5.zip
)
# For Windows: Prevent overriding the parent project's compiler/linker settings
set(gtest_force_shared_crt ON CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(googletest)

enable_testing()

add_executable(
        counter_test
        counter_test.cpp
)
target_link_libraries(
        counter_test
        gtest_main
)
target_link_libraries(
        counter_test
        gmock_main
)
target_link_libraries(
        counter_test
        lincheck_library
)

include(GoogleTest)
gtest_discover_tests(counter_test)
