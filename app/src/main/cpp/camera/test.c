#include <stdio.h>
#include <unistd.h>
#include <sys/types.h>
#include <string.h>

void print_progname() {
    char path[1024];
    sprintf(path, "/proc/%d/cmdline", getpid());
    FILE *f = fopen(path, "r");
    if (f) {
        char cmdline[1024];
        fread(cmdline, 1, sizeof(cmdline), f);
        printf("cmdline: %s\n", cmdline);
        fclose(f);
    }
}
