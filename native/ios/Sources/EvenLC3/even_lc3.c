#include "even_lc3.h"
#include "lc3.h"
#include <stdlib.h>

/* Allocate zeroed, naturally aligned state exactly as the previous Dart calloc. */
void *even_lc3_create(void) {
    void *memory = calloc(1, lc3_decoder_size(10000, 16000));
    if (!memory) return NULL;
    if (!lc3_setup_decoder(10000, 16000, 0, memory)) {
        free(memory);
        return NULL;
    }
    return memory;
}

/* Reject incomplete frames before mutating history; PLC status is preserved. */
int even_lc3_decode(void *decoder, const uint8_t *frame, size_t count, uint8_t *pcm) {
    if (!decoder || !frame || count != 40 || !pcm) return -1;
    int16_t samples[160];
    int status = lc3_decode(decoder, frame, 40, LC3_PCM_FORMAT_S16, samples, 1);
    if (status < 0) return status;
    for (int i = 0; i < 160; ++i) {
        pcm[2*i] = (uint8_t)samples[i];
        pcm[2*i+1] = (uint8_t)((uint16_t)samples[i] >> 8);
    }
    return status;
}

/* Owners dispose on stream replacement to prevent cross-session predictor state. */
void even_lc3_destroy(void *decoder) { free(decoder); }
