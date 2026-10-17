cmake_minimum_required(VERSION 3.22.1)
project(antidigitalsmod LANGUAGES CXX)

add_library(antidigitalsmod SHARED anti_digital_liquid.cpp)
find_library(log-lib log)
target_compile_features(antidigitalsmod PRIVATE cxx_std_17)
target_compile_options(antidigitalsmod PRIVATE -Wall -Wextra -Werror=return-type -fvisibility=hidden)
target_link_libraries(antidigitalsmod ${log-lib})
