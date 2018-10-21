#ifndef _MAGISKBOOT_H_
#define _MAGISKBOOT_H_

#include <sys/types.h>

#include "logging.h"
#include "format.h"

#define KERNEL_FILE     "kernel"
#define RAMDISK_FILE    "ramdisk.cpio"
#define SECOND_FILE     "second"
#define EXTRA_FILE      "extra"
#define DTB_FILE        "dtb"
#define RECV_DTBO_FILE  "recovery_dtbo"
#define NEW_BOOT        "new-boot.img"

// Main entries
int unpack(const char *image);
void repack(const char* orig_image, const char* out_image);
void hexpatch(const char *image, const char *from, const char *to);
int cpio_commands(int argc, char *argv[]);
void comp_file(const char *method, const char *from, const char *to);
void decomp_file(char *from, const char *to);
int dtb_commands(const char *cmd, int argc, char *argv[]);

// Compressions
size_t gzip(int mode, int fd, const void *buf, size_t size);
size_t lzma(int mode, int fd, const void *buf, size_t size);
size_t lz4(int mode, int fd, const void *buf, size_t size);
size_t bzip2(int mode, int fd, const void *buf, size_t size);
size_t lz4_legacy(int mode, int fd, const void *buf, size_t size);
long long comp(format_t type, int to, const void *from, size_t size);
long long decomp(format_t type, int to, const void *from, size_t size);

// Pattern
int patch_verity(void **buf, uint32_t *size, int patch);
void patch_encryption(void **buf, uint32_t *size);

#endif
