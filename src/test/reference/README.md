# Native hash reference vectors

`hash-vectors.c` generates `src/test/resources/seeded-hash-vectors.csv` using the
upstream implementations, independently of the Java ports. Normal Gradle tests
read the committed vectors without native code, network access, or extra tools.

Pinned references:

- [rapidhash v3 / Nano](https://github.com/Nicoshev/rapidhash/blob/1ae7842f59f25929a338e5a76e3fa350673e48e1/rapidhash.h),
  default secrets, `RAPIDHASH_FAST`, compact loop.
- [Komihash 5.34](https://github.com/avaneev/komihash/blob/50a15150746eaf7db32558388a27c6628a46cdee/komihash.h).

To regenerate, download these two headers into a scratch directory, compile the
generator with a C99 compiler (`cc -O2 -std=c99 -I<headers> hash-vectors.c -o vectors`),
and redirect its output to the CSV. The initial vectors were generated with Zig
0.14.1's C compiler on Windows x64. Zig is not a project dependency.

The vectors cover every length 0–257 and larger block boundaries through 4,097
bytes, using sequential high-bit bytes, zeros, and 0xff. Each input is tested
with seeds 0, 1, 0x9e3779b97f4a7c15, 0xffffffffffffffff, and 0x8000000000000000.
The two Bloom seeds are 0 and 0x9e3779b97f4a7c15. Hexadecimal results represent
unsigned 64-bit values, independent of the host's byte order.
