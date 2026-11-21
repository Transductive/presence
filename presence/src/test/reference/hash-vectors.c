/* Optional fixture generator; compiled against the pinned upstream C headers.
 * Normal Java tests read the checked-in CSV and do not require a C compiler.
 * See README.md in this directory for the exact references and input recipe.
 */
#include <inttypes.h>
#include <stdio.h>
#include "rapidhash.h"
#include "komihash.h"

int main(void) {
    static const uint64_t seeds[] = {0, 1, UINT64_C(0x9e3779b97f4a7c15), UINT64_MAX, UINT64_C(0x8000000000000000)};
    static const size_t extra[] = {335, 336, 337, 447, 448, 449, 511, 512, 513, 1023, 1024, 1025, 4095, 4096, 4097};
    unsigned char data[4097];
    puts("pattern,length,seed,rapid,nano,komi");
    for (int pattern = 0; pattern < 3; pattern++) {
        for (size_t i = 0; i < sizeof(data); i++) {
            data[i] = pattern == 0 ? (unsigned char)(i * 137 + 29) : pattern == 1 ? 0 : 255;
        }
        for (size_t n = 0; n < 258 + sizeof(extra) / sizeof(extra[0]); n++) {
            size_t length = n < 258 ? n : extra[n - 258];
            for (size_t s = 0; s < sizeof(seeds) / sizeof(seeds[0]); s++) {
                uint64_t seed = seeds[s];
                printf("%d,%zu,%016" PRIx64 ",%016" PRIx64 ",%016" PRIx64 ",%016" PRIx64 "\n",
                       pattern, length, seed, rapidhash_withSeed(data, length, seed),
                       rapidhashNano_withSeed(data, length, seed), komihash(data, length, seed));
            }
        }
    }
    return 0;
}
