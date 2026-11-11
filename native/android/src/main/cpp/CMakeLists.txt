cmake_minimum_required(VERSION 3.22.1)
project(even_lc3 C)
# Preserve the production decoder exactly; its Android slices already support
# 16KB pages. Source recompilation changes floating-point predictor behavior.
add_library(lc3 SHARED IMPORTED)
set_target_properties(lc3 PROPERTIES IMPORTED_LOCATION
    "${EVEN_LC3_LIB_DIR}/${ANDROID_ABI}/libeg3.so")
add_library(even_lc3 SHARED
    ../../../../ios/Sources/EvenLC3/even_lc3.c even_lc3_jni.c)
target_include_directories(even_lc3 PRIVATE
    ../../../../../third_party/flutter_ezw_lc3/lib/lc3_include ../../../../ios/Sources/EvenLC3/include)
target_compile_options(even_lc3 PRIVATE -O2)
target_link_options(even_lc3 PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(even_lc3 lc3)
