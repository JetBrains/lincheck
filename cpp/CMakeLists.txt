cmake_minimum_required(VERSION 3.16)
project(lincheck_test)

set(CMAKE_CXX_STANDARD 17)

# add lincheck shared library
add_library(lincheck_library SHARED IMPORTED GLOBAL)
if(EXISTS "${PROJECT_SOURCE_DIR}/../build/bin/native/releaseShared/libnative.so") #linux .so
    set_target_properties(lincheck_library PROPERTIES IMPORTED_LOCATION "${PROJECT_SOURCE_DIR}/../build/bin/native/releaseShared/libnative.so")
else() # mac .dylib
    set_target_properties(lincheck_library PROPERTIES IMPORTED_LOCATION "${PROJECT_SOURCE_DIR}/../build/bin/native/releaseShared/libnative.dylib")
endif()

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
if (NOT libcds_POPULATED)
    FetchContent_Populate(libcds)
    add_subdirectory(${libcds_SOURCE_DIR} ${libcds_BINARY_DIR})
endif ()

# add folly
FetchContent_Declare(
        folly
        GIT_REPOSITORY https://github.com/facebook/folly
        GIT_TAG master
        GIT_SHALLOW TRUE
)

FetchContent_GetProperties(folly)
if (NOT folly_POPULATED)
    FetchContent_Populate(folly)
    add_subdirectory(${folly_SOURCE_DIR} ${folly_BINARY_DIR})
endif ()

# add libcuckoo
FetchContent_Declare(
        libcuckoo
        GIT_REPOSITORY https://github.com/efficient/libcuckoo
        GIT_TAG master
        GIT_SHALLOW TRUE
)

FetchContent_GetProperties(libcuckoo)
if (NOT libcuckoo_POPULATED)
    FetchContent_Populate(libcuckoo)
    add_subdirectory(${libcuckoo_SOURCE_DIR} ${libcuckoo_BINARY_DIR})
    include_directories(${libcuckoo_SOURCE_DIR})
endif ()

# add boost
set(Boost_USE_STATIC_LIBS OFF)
set(Boost_USE_MULTITHREADED ON)
set(Boost_USE_STATIC_RUNTIME OFF)
find_package(Boost REQUIRED COMPONENTS thread program_options)

add_executable(
        tests
        counter_test.cpp
        libcds_test.cpp
        libcuckoo_test.cpp
        folly_test.cpp
        boost_lockfree_test.cpp
)

enable_testing()

target_link_libraries(tests gtest_main)
target_link_libraries(tests gmock_main)
target_link_libraries(tests lincheck_library)
target_link_libraries(tests cds)
target_link_libraries(tests libcuckoo)
target_link_libraries(tests folly)
set(Boost_USE_STATIC_LIBS OFF)
set(Boost_USE_MULTITHREADED ON)
set(Boost_USE_STATIC_RUNTIME OFF)
include_directories(${Boost_INCLUDE_DIR} ${BOOST_LOCKFREE_DIR})
target_link_libraries(tests ${Boost_LIBRARIES})

include(GoogleTest)
gtest_discover_tests(tests)
