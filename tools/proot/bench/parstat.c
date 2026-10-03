/* T threads each stat()ing a path for D seconds: aggregate trapped-syscall throughput. */
#define _GNU_SOURCE
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/stat.h>
#include <time.h>
static const char *path; static volatile int stop; static long counts[64];
static void *run(void *a) { long i = (long)a, n = 0; struct stat st; while (!stop) { stat(path, &st); n++; } counts[i] = n; return 0; }
int main(int c, char **v) {
  path = v[1]; int T = atoi(v[2]); double D = c > 3 ? atof(v[3]) : 2;
  pthread_t t[64]; for (long i = 0; i < T; i++) pthread_create(&t[i], 0, run, (void *)i);
  struct timespec ts = {(time_t)D, (long)((D - (long)D) * 1e9)}; nanosleep(&ts, 0); stop = 1;
  long tot = 0; for (int i = 0; i < T; i++) { pthread_join(t[i], 0); tot += counts[i]; }
  printf("threads=%d  %8.0f stat/s total  %6.1f us/stat per thread\n", T, tot / D, T * D * 1e6 / tot);
  return 0; }
