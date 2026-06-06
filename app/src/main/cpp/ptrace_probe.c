/*
 * ptrace_probe.c — self-contained ptrace self-test for arm64 Android.
 * Compile: aarch64-linux-android29-clang ptrace_probe.c -o libptraceprobe.so -static-pie
 *
 * Tests:
 *   1. fork()
 *   2. CHILD:  ptrace(PTRACE_TRACEME) → raise(SIGSTOP) → _exit(42)
 *   3. PARENT: waitpid → WIFSTOPPED?  → PTRACE_PEEKDATA → PTRACE_CONT → wait exit
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <unistd.h>
#include <signal.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <sys/ptrace.h>
#include <stdint.h>

/* A global variable whose address we'll read via PTRACE_PEEKDATA from parent.
   The parent and child share the same binary layout so the VA is known. */
static volatile long probe_sentinel = 0xdeadbeefL;

static void child_main(void) {
    long r = ptrace(PTRACE_TRACEME, 0, (void*)0, (void*)0);
    if (r == -1) {
        printf("PTRACE_TRACEME: FAIL errno=%d (%s)\n", errno, strerror(errno));
        fflush(stdout);
        _exit(1);
    }
    printf("PTRACE_TRACEME: OK\n");
    fflush(stdout);

    /* Signal parent to peek at us */
    raise(SIGSTOP);

    /* Parent will PTRACE_CONT us; we then exit with 42 */
    _exit(42);
}

int main(void) {
    /* Flush stdout line-by-line so parent can read partial output */
    setvbuf(stdout, NULL, _IOLBF, 0);

    pid_t child = fork();
    if (child == -1) {
        printf("fork: FAIL errno=%d (%s)\n", errno, strerror(errno));
        return 1;
    }

    if (child == 0) {
        child_main();
        /* not reached */
        _exit(99);
    }

    /* ---- PARENT ---- */
    int status = 0;
    pid_t w = waitpid(child, &status, 0);
    if (w == -1) {
        printf("waitpid: FAIL errno=%d (%s)\n", errno, strerror(errno));
        kill(child, SIGKILL);
        return 1;
    }

    /* The child may have printed PTRACE_TRACEME result to its own stdout
       (shared fd since we didn't redirect), so it should appear above. */

    if (WIFSTOPPED(status)) {
        printf("waitpid/WIFSTOPPED: yes (signal=%d)\n", WSTOPSIG(status));
    } else if (WIFEXITED(status)) {
        /* TRACEME failed → child exited early */
        printf("waitpid/WIFSTOPPED: no — child exited early code=%d\n", WEXITSTATUS(status));
        return 1;
    } else {
        printf("waitpid/WIFSTOPPED: no (raw status=0x%x)\n", status);
    }
    fflush(stdout);

    /* Try PTRACE_PEEKDATA at the address of probe_sentinel (a known mapped VA).
       On Android seccomp-blocked ptrace this returns EPERM or EIO. */
    errno = 0;
    long val = ptrace(PTRACE_PEEKDATA, child,
                      (void*)(uintptr_t)&probe_sentinel, (void*)0);
    if (errno != 0) {
        printf("PTRACE_PEEKDATA: FAIL errno=%d (%s)\n", errno, strerror(errno));
    } else {
        printf("PTRACE_PEEKDATA: OK value=0x%lx\n", (unsigned long)val);
    }
    fflush(stdout);

    /* Continue the child */
    long cr = ptrace(PTRACE_CONT, child, (void*)0, (void*)0);
    if (cr == -1) {
        printf("PTRACE_CONT: FAIL errno=%d (%s)\n", errno, strerror(errno));
        kill(child, SIGKILL);
        waitpid(child, NULL, 0);
        return 1;
    }

    /* Wait for child to exit */
    int status2 = 0;
    waitpid(child, &status2, 0);
    if (WIFEXITED(status2)) {
        printf("PTRACE_CONT + exit: code=%d\n", WEXITSTATUS(status2));
    } else if (WIFSIGNALED(status2)) {
        printf("PTRACE_CONT + exit: killed by signal %d\n", WTERMSIG(status2));
    } else {
        printf("PTRACE_CONT + exit: unknown status=0x%x\n", status2);
    }
    fflush(stdout);

    return 0;
}
