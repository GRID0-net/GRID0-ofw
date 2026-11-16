import Foundation

// Thin wrappers over the vendored single-file zstd library, matching the
// Python proxy's zstandard settings: level 3, content size written, no
// checksum, and a 1 MB cap on decompressed output.

enum ZstdError: Error {
    case failed(String)
}

func zstdCompress(_ data: [UInt8]) -> [UInt8] {
    let bound = ZSTD_compressBound(data.count)
    var out = [UInt8](repeating: 0, count: bound)
    let n = data.withUnsafeBufferPointer { src in
        out.withUnsafeMutableBufferPointer { dst in
            ZSTD_compress(dst.baseAddress, bound, src.baseAddress, data.count, 3)
        }
    }
    if ZSTD_isError(n) != 0 {
        return data
    }
    return Array(out[0..<n])
}

func zstdDecompress(_ data: [UInt8]) throws -> [UInt8] {
    let cap = 1 << 20
    var out = [UInt8](repeating: 0, count: cap)
    let n = data.withUnsafeBufferPointer { src in
        out.withUnsafeMutableBufferPointer { dst in
            ZSTD_decompress(dst.baseAddress, cap, src.baseAddress, data.count)
        }
    }
    if ZSTD_isError(n) != 0 {
        let msg = String(cString: ZSTD_getErrorName(n))
        throw ZstdError.failed(msg)
    }
    return Array(out[0..<n])
}
