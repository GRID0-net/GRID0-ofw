import Foundation
import CommonCrypto

// AES and hashing helpers matching the Python proxy's pycryptodome calls.
// AES-GCM is implemented over CommonCrypto's block function because the
// Splatoon game packets use an 8-byte GCM tag, which CryptoKit cannot do.

func aesEncryptBlock(key: [UInt8], block: [UInt8]) -> [UInt8] {
    var out = [UInt8](repeating: 0, count: 16)
    var outLen = 0
    let status = CCCrypt(
        CCOperation(kCCEncrypt), CCAlgorithm(kCCAlgorithmAES), CCOptions(kCCOptionECBMode),
        key, key.count, nil,
        block, block.count,
        &out, out.count, &outLen)
    precondition(status == kCCSuccess && outLen == 16)
    return out
}

func hmacSHA256(key: [UInt8], data: [UInt8]) -> [UInt8] {
    var out = [UInt8](repeating: 0, count: Int(CC_SHA256_DIGEST_LENGTH))
    CCHmac(CCHmacAlgorithm(kCCHmacAlgSHA256), key, key.count, data, data.count, &out)
    return out
}

func sha256(_ data: [UInt8]) -> [UInt8] {
    var out = [UInt8](repeating: 0, count: Int(CC_SHA256_DIGEST_LENGTH))
    CC_SHA256(data, CC_LONG(data.count), &out)
    return out
}

func randomBytes(_ n: Int) -> [UInt8] {
    var out = [UInt8](repeating: 0, count: n)
    _ = SecRandomCopyBytes(kSecRandomDefault, n, &out)
    return out
}

private func gfMul(_ xIn: [UInt8], _ h: [UInt8]) -> [UInt8] {
    var z = [UInt8](repeating: 0, count: 16)
    var v = h
    var x = xIn
    for i in 0..<16 {
        for j in (0..<8).reversed() {
            if (x[i] >> j) & 1 == 1 {
                for k in 0..<16 { z[k] ^= v[k] }
            }
            let lsb = v[15] & 1
            for k in (1..<16).reversed() {
                v[k] = (v[k] >> 1) | ((v[k - 1] & 1) << 7)
            }
            v[0] >>= 1
            if lsb == 1 { v[0] ^= 0xE1 }
        }
    }
    return z
}

private func ghash(h: [UInt8], ciphertext: [UInt8]) -> [UInt8] {
    var y = [UInt8](repeating: 0, count: 16)
    func absorb(_ block: [UInt8]) {
        for i in 0..<16 { y[i] ^= block[i] }
        y = gfMul(y, h)
    }
    var off = 0
    while off + 16 <= ciphertext.count {
        absorb(Array(ciphertext[off..<off + 16]))
        off += 16
    }
    if off < ciphertext.count {
        var block = [UInt8](repeating: 0, count: 16)
        for i in 0..<(ciphertext.count - off) { block[i] = ciphertext[off + i] }
        absorb(block)
    }
    var lenBlock = [UInt8](repeating: 0, count: 16)
    let bits = UInt64(ciphertext.count) * 8
    for i in 0..<8 { lenBlock[15 - i] = UInt8((bits >> (8 * i)) & 0xFF) }
    absorb(lenBlock)
    return y
}

private func gcmCrypt(key: [UInt8], j0: [UInt8], input: [UInt8]) -> [UInt8] {
    var out = [UInt8](repeating: 0, count: input.count)
    var counter = j0
    func inc32(_ c: inout [UInt8]) {
        var v = UInt32(c[12]) << 24 | UInt32(c[13]) << 16 | UInt32(c[14]) << 8 | UInt32(c[15])
        v &+= 1
        c[12] = UInt8((v >> 24) & 0xFF)
        c[13] = UInt8((v >> 16) & 0xFF)
        c[14] = UInt8((v >> 8) & 0xFF)
        c[15] = UInt8(v & 0xFF)
    }
    var off = 0
    while off < input.count {
        inc32(&counter)
        let stream = aesEncryptBlock(key: key, block: counter)
        let n = min(16, input.count - off)
        for i in 0..<n { out[off + i] = input[off + i] ^ stream[i] }
        off += n
    }
    return out
}

private func gcmTag(key: [UInt8], nonce: [UInt8], ciphertext: [UInt8]) -> [UInt8] {
    let h = aesEncryptBlock(key: key, block: [UInt8](repeating: 0, count: 16))
    var j0 = nonce + [0, 0, 0, 1]
    let s = ghash(h: h, ciphertext: ciphertext)
    let ej0 = aesEncryptBlock(key: key, block: j0)
    var tag = [UInt8](repeating: 0, count: 16)
    for i in 0..<16 { tag[i] = s[i] ^ ej0[i] }
    return tag
}

// Returns ciphertext and the full 16-byte tag.
func aesGCMEncrypt(key: [UInt8], nonce: [UInt8], plaintext: [UInt8]) -> (ct: [UInt8], tag: [UInt8]) {
    precondition(nonce.count == 12)
    let j0 = nonce + [0, 0, 0, 1]
    let ct = gcmCrypt(key: key, j0: j0, input: plaintext)
    return (ct, gcmTag(key: key, nonce: nonce, ciphertext: ct))
}

// Verifies the first tag.count bytes of the computed tag (the Python proxy
// uses tag lengths of 16 for discovery and session traffic, 8 for Splatoon
// game packets). Returns the plaintext or nil.
func aesGCMDecrypt(key: [UInt8], nonce: [UInt8], ciphertext: [UInt8], tag: [UInt8]) -> [UInt8]? {
    precondition(nonce.count == 12)
    let full = gcmTag(key: key, nonce: nonce, ciphertext: ciphertext)
    guard tag.count >= 1 && tag.count <= 16 else { return nil }
    for i in 0..<tag.count where full[i] != tag[i] { return nil }
    let j0 = nonce + [0, 0, 0, 1]
    return gcmCrypt(key: key, j0: j0, input: ciphertext)
}
