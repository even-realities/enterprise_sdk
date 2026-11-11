#ifndef EVEN_LC3_H
#define EVEN_LC3_H
#include <stdint.h>
#include <stddef.h>
#ifdef __cplusplus
extern "C" {
#endif
/* Each connection owns a decoder; callers serialize decode/reset/destruction.
 * Fixed G2 format matches the retained Dart codec: 10ms, 16kHz, mono S16LE. */
void *even_lc3_create(void);
int even_lc3_decode(void *decoder, const uint8_t *frame, size_t count, uint8_t *pcm);
void even_lc3_destroy(void *decoder);
#ifdef __cplusplus
}
#endif
#endif
