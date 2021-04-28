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

# add turf for junction
FetchContent_Declare(
        turf
        GIT_REPOSITORY https://github.com/preshing/turf
        GIT_TAG master
        GIT_SHALLOW TRUE
)

FetchContent_GetProperties(turf)
if(NOT turf_POPULATED)
    FetchContent_Populate(turf)
    add_subdirectory(${turf_SOURCE_DIR} ${turf_BINARY_DIR})
endif()

set(TURF_ROOT "${turf_SOURCE_DIR}" CACHE STRING "Path to Turf")

# add junction
FetchContent_Declare(
        junction
        GIT_REPOSITORY https://github.com/Krock21rus/junction
        GIT_TAG master
        GIT_SHALLOW TRUE
)

FetchContent_GetProperties(junction)
if(NOT junction_POPULATED)
    FetchContent_Populate(junction)
    add_subdirectory(${junction_SOURCE_DIR} ${junction_BINARY_DIR})
endif()

enable_testing()

add_executable(
        tests
        counter_test.cpp
        libcds_msqueue_dhp_test.cpp
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

include(GoogleTest)
gtest_discover_tests(tests)
