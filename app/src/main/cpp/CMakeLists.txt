cmake_minimum_required(VERSION 3.22.1)
project(introspector)

set(SCANMEM_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../external/scanmem")

add_executable(scanmem
    ${SCANMEM_DIR}/main.c
    ${SCANMEM_DIR}/value.c
    ${SCANMEM_DIR}/scanroutines.c
    ${SCANMEM_DIR}/maps.c
    ${SCANMEM_DIR}/ptrace.c
    ${SCANMEM_DIR}/handlers.c
    ${SCANMEM_DIR}/commands.c
    ${SCANMEM_DIR}/targetmem.c
    ${SCANMEM_DIR}/show_message.c
    ${SCANMEM_DIR}/scanmem.c
    ${SCANMEM_DIR}/menu.c
    ${SCANMEM_DIR}/readline.c
    ${SCANMEM_DIR}/getline.c
    ${SCANMEM_DIR}/interrupt.c
    ${SCANMEM_DIR}/sets.c
    ${SCANMEM_DIR}/common.c
)

# Our config.h (in CMAKE_CURRENT_SOURCE_DIR) takes precedence over any
# autoconf-generated one that might be present in the scanmem directory.
target_include_directories(scanmem PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${SCANMEM_DIR}
)

add_executable(memread ${CMAKE_CURRENT_SOURCE_DIR}/memread.c)

# Deploy both binaries into assets so Gradle packages them in the APK.
set(ASSETS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../assets/${ANDROID_ABI}")
add_custom_command(TARGET scanmem POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory "${ASSETS_DIR}"
    COMMAND ${CMAKE_COMMAND} -E copy "$<TARGET_FILE:scanmem>" "${ASSETS_DIR}/scanmem"
    COMMENT "Deploying scanmem to assets/${ANDROID_ABI}"
)
add_custom_command(TARGET memread POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory "${ASSETS_DIR}"
    COMMAND ${CMAKE_COMMAND} -E copy "$<TARGET_FILE:memread>" "${ASSETS_DIR}/memread"
    COMMENT "Deploying memread to assets/${ANDROID_ABI}"
)
