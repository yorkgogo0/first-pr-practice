cmake_minimum_required(VERSION 3.20)
project(graalexec-jni)

set(GS2PARSER_BUILD_GS2TEST OFF CACHE BOOL "" FORCE)
add_subdirectory(gs2-parser gs2-parser-build)

add_library(graalexec SHARED graalexec/graalexec.cpp)
target_link_libraries(graalexec PRIVATE gs2compiler log android)
