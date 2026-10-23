cmake_minimum_required(VERSION 3.22.1)
project(organ)

find_package(oboe REQUIRED CONFIG)

add_library(organ SHARED native-lib.cpp)

# Always optimize the audio engine, even in debug builds. A debug build (-O0) made sample decoding
# many times slower and risks audio dropouts.
set(CMAKE_CXX_FLAGS_DEBUG "-O2 -g0")
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG")
target_compile_options(organ PRIVATE -O3)

target_link_libraries(organ oboe::oboe log android)
