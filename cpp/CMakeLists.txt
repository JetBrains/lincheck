cmake_minimum_required(VERSION 3.19)
project(project_name)

set(CMAKE_CXX_STANDARD 20)

add_executable(project_name main.cpp)

link_directories(/home/krock21/IdeaProjects/kotlinx-lincheck//build/bin/native/debugShared/)
target_link_libraries(project_name native)
