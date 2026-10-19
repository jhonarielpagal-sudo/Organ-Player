cmake_minimum_required(VERSION 3.22.1)
project(organ)

find_package(oboe REQUIRED CONFIG)

add_library(organ SHARED native-lib.cpp)
target_link_libraries(organ oboe::oboe log android)
