cmake_minimum_required(VERSION 3.16)
project(lincheck_test)

set(CMAKE_CXX_STANDARD 20)

# add lincheck shared library
add_library(lincheck_library SHARED IMPORTED GLOBAL)
set_target_properties(lincheck_library PROPERTIES IMPORTED_LOCATION "${PROJECT_SOURCE_DIR}/../build/bin/native/debugShared/libnative.so")

# add gtest
include(FetchContent)
FetchContent_Declare(
        googletest
        URL https://github.com/google/googletest/archive/609281088cfefc76f9d0ce82e1ff6c30cc3591e5.zip
)
# For Windows: Prevent overriding the parent project's compiler/linker settings
set(gtest_force_shared_crt ON CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(googletest)

# add libcds
FetchContent_Declare(
        libcds
        GIT_REPOSITORY https://github.com/khizmax/libcds
        GIT_TAG master
        GIT_SHALLOW TRUE
)

FetchContent_GetProperties(libcds)
if(NOT libcds_POPULATED)
    FetchContent_Populate(libcds)
    add_subdirectory(${libcds_SOURCE_DIR} ${libcds_BINARY_DIR})
endif()

add_subdirectory("junction/junction" junction)

enable_testing()

add_executable(
        tests
        counter_test.cpp
        libcds_msqueue_dhp_test.cpp
        junction_test.cpp
)
target_link_libraries(
        tests
        gtest_main
)
target_link_libraries(
        tests
        gmock_main
)
target_link_libraries(
        tests
        lincheck_library
)
target_link_libraries(
        tests
        cds
)
target_link_libraries(
        tests
        junction
)

include_directories(${JUNCTION_ALL_INCLUDE_DIRS})
target_link_libraries(tests ${JUNCTION_ALL_LIBRARIES})

include(GoogleTest)
gtest_discover_tests(tests)
