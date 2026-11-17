/* SPDX-License-Identifier: GPL-3.0-only */
#include <stdio.h>
/* Exported name and recognizable data for independent parser checks. */
__attribute__((visibility("default"),noinline)) int die_fixture_export(void) {
    return puts("DIE_FIXTURE_MARKER");
}
int main(void){return die_fixture_export()<0;}
