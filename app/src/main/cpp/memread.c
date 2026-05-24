/*
 * memread — reads int32 values from /proc/<pid>/mem at given hex addresses.
 * Reads whitespace-separated hex addresses from stdin, prints "addr:value\n" for each.
 * Used for multi-round memory narrowing without re-scanning the full address space.
 */
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <fcntl.h>
#include <unistd.h>

int main(int argc, char *argv[]) {
    if (argc < 2) {
        fprintf(stderr, "usage: memread <pid>\n");
        return 1;
    }

    char path[64];
    snprintf(path, sizeof(path), "/proc/%s/mem", argv[1]);
    int fd = open(path, O_RDONLY);
    if (fd < 0) {
        perror("open");
        return 1;
    }

    char addr_str[32];
    while (scanf("%31s", addr_str) == 1) {
        unsigned long addr = strtoul(addr_str, NULL, 16);
        int32_t val = 0;
        if (pread(fd, &val, sizeof(val), (off_t)addr) == (ssize_t)sizeof(val)) {
            printf("%s:%d\n", addr_str, val);
        }
    }

    close(fd);
    return 0;
}
