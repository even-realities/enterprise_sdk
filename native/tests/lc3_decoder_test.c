#include "even_lc3.h"
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
/* Golden uses the retained Flutter LC3 archive. A one-LSB tolerance covers
 * compiler floating rounding while guarding predictor state across frames. */
int main(int argc, char **argv) {
    assert(argc == 4);
    FILE *f = fopen(argv[1], "rb"), *g = fopen(argv[2], "rb");
    assert(f && g);
    uint8_t frame[40], actual[320], expected[320];
    assert(fread(frame, 1, 40, f) == 40);
    void *d = even_lc3_create(); assert(d);
    assert(even_lc3_decode(d, frame, 39, actual) == -1);
    for (int n = 0; n < 6; ++n) {
        if (n == 5) { even_lc3_destroy(d); d = even_lc3_create(); rewind(g); }
        assert(even_lc3_decode(d, frame, 40, actual) == 0);
        assert(fread(expected, 1, 320, g) == 320);
        for (int i = 0; i < 160; ++i) {
            int16_t a = (int16_t)(actual[2*i] | actual[2*i+1] << 8);
            int16_t e = (int16_t)(expected[2*i] | expected[2*i+1] << 8);
            assert(abs((int)a-e) <= 1);
        }
    }
    even_lc3_destroy(d); fclose(f); fclose(g);
    // This reference was emitted by the production Apple arm64 codec. Each
    // record carries one encoded frame followed by the expected PCM16LE.
#if defined(__aarch64__)
    FILE *stream = fopen(argv[3], "rb");
    assert(stream != NULL);
    d = even_lc3_create();
    assert(d != NULL);
    for (int index = 0; index < 256; index++) {
        assert(fread(frame, 1, sizeof(frame), stream) == sizeof(frame));
        assert(fread(expected, 1, sizeof(expected), stream) == sizeof(expected));
        assert(even_lc3_decode(d, frame, sizeof(frame), actual) == 0);
        assert(memcmp(actual, expected, sizeof(actual)) == 0);
    }
    assert(fgetc(stream) == EOF);
    fclose(stream);
    even_lc3_destroy(d);
    puts("LC3 Apple arm64 continuous parity: 40960 samples matched exactly");
#endif
    puts("LC3 golden: 5 consecutive frames, invalid length, reset passed");
}
