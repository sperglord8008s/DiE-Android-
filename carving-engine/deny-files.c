// SPDX-License-Identifier: GPL-3.0-only
#include <errno.h>
#include <stdio.h>

/* Every upstream translation unit redirects path opens and renames here.
 * Neither auxiliary signature files nor recovery output may be opened. The
 * adapter supplies buffers plus a bounded read-only relative FILE view for the
 * reviewed PDF file_check only. No file_rename callback is invoked.
 */
FILE *die_photorec_denied_fopen(const char *path, const char *mode) {
    (void)path;
    (void)mode;
    errno = EACCES;
    return NULL;
}

int die_photorec_denied_rename(const char *from, const char *to) {
    (void)from;
    (void)to;
    errno = EACCES;
    return -1;
}
