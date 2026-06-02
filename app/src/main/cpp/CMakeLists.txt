cmake_minimum_required(VERSION 3.22.1)
project(tempoz)

find_package(oboe REQUIRED CONFIG)

file(GLOB CPP_SOURCES CONFIGURE_DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/*.cpp)

# sonic.c is a C file (not captured by the *.cpp glob above); add explicitly.
add_library(tempoz SHARED ${CPP_SOURCES} ${CMAKE_CURRENT_SOURCE_DIR}/sonic.c)

target_link_libraries(tempoz
    oboe::oboe
    mediandk
    android
    log
)
