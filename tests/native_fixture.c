/* SPDX-License-Identifier: GPL-3.0-only */
#include <stdio.h>
/* Preserve an unsigned value whose bit pattern must not become JSON null. */
__asm__(".globl die_fixture_absolute_max\n.set die_fixture_absolute_max, -1");
/* Exported name and recognizable data for independent parser checks. */
__attribute__((visibility("default"),noinline)) int die_fixture_export(void) {
    return puts("DIE_FIXTURE_MARKER");
}
int main(void){return die_fixture_export()<0;}
