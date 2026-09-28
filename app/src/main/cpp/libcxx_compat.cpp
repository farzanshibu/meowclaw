// The prebuilt 32-bit ARM Needle engine was compiled against a newer libc++
// that exports std::__hash_memory; the NDK's static libc++ does not. The
// engine only uses it to hash keys of in-process hash tables, so any stable
// hash is correct here. 64-bit ARM links without this file.

#include <cstddef>
#include <cstdint>

namespace std {
inline namespace __ndk1 {

__attribute__((visibility("hidden"))) size_t __hash_memory(const void* data, size_t size) noexcept {
    // FNV-1a
    const auto* bytes = static_cast<const unsigned char*>(data);
    uint32_t hash = 2166136261u;
    for (size_t i = 0; i < size; ++i) {
        hash ^= bytes[i];
        hash *= 16777619u;
    }
    return hash;
}

}  // namespace __ndk1
}  // namespace std
