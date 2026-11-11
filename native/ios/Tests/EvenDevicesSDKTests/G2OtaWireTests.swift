import Foundation
import XCTest
@testable import EvenDevicesSDK

final class G2OtaWireTests: XCTestCase {
    private struct Vectors: Decodable {
        let sync_id: Int
        let start_hex, information_header_hex, information_hex, result_check_hex: String
        let start_ack_hex, raw_ack_hex, install_notify_hex: String
        struct Raw: Decodable { let write_limit: Int; let payload_hex: String; let frames_hex: [String] }
        let raw: Raw
    }

    private func load() throws -> Vectors {
        let path = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("protocol/g2_ota_vectors.json")
        return try JSONDecoder().decode(Vectors.self, from: Data(contentsOf: path))
    }

    private func hex(_ text: String) -> Data {
        let bytes = Array(text.utf8)
        return Data(stride(from: 0, to: bytes.count, by: 2).map {
            UInt8(String(decoding: bytes[$0..<$0 + 2], as: UTF8.self), radix: 16)!
        })
    }

    func testControlsAndCrcOnlyFinalFragmentMatchDartVectors() throws {
        let v = try load()
        XCTAssertEqual(G2OtaWire.control(cid: 0, sync: v.sync_id), hex(v.start_hex))
        XCTAssertEqual(G2OtaWire.control(cid: 1, sync: v.sync_id, payload: hex(v.information_header_hex)),
                       hex(v.information_hex))
        XCTAssertEqual(G2OtaWire.control(cid: 3, sync: v.sync_id), hex(v.result_check_hex))
        XCTAssertEqual(try G2OtaWire.window(hex(v.raw.payload_hex), sync: v.sync_id, maxWrite: v.raw.write_limit),
                       v.raw.frames_hex.map(hex))
    }

    func testWindowBoundariesPreserveEveryByteAndFinalCrc() throws {
        for size in [1, 140, 141, 142, 143, 283, 4096] {
            let body = Data((0..<size).map { UInt8(truncatingIfNeeded: $0) })
            let parts = Array(try G2OtaWire.window(body, sync: 255, maxWrite: 150).dropFirst())
            var decoded = Data()
            for (i, part) in parts.enumerated() {
                XCTAssertLessThanOrEqual(part.count, 150)
                XCTAssertEqual(Int(part[4]), parts.count)
                XCTAssertEqual(Int(part[5]), i + 1)
                decoded.append(part[8..<part.count - (i + 1 == parts.count ? 2 : 0)])
            }
            XCTAssertEqual(body, decoded)
            let crc = G2AuthenticationWire.crc16(Array(body))
            XCTAssertEqual(Array(parts.last!.suffix(2)), [UInt8(truncatingIfNeeded: crc), UInt8(crc >> 8)])
        }
        XCTAssertThrowsError(try G2OtaWire.window(Data(count: 4097), sync: 1, maxWrite: 150))
    }

    func testRepliesRequireTheSingleMatchingFrameAndValidatedCrc() throws {
        let v = try load()
        let raw = hex(v.raw_ack_hex)
        XCTAssertEqual(G2OtaWire.reply(raw + Data([99]), expectedCid: 2, expectedSync: v.sync_id), 0)
        XCTAssertEqual(G2OtaWire.reply(hex(v.start_ack_hex), expectedCid: 0, expectedSync: v.sync_id), 0)
        XCTAssertEqual(G2OtaWire.notify(hex(v.install_notify_hex)), 9)
        XCTAssertNil(G2OtaWire.reply(hex(v.install_notify_hex), expectedCid: 3, expectedSync: v.sync_id))
        XCTAssertNil(G2OtaWire.notify(raw))
        XCTAssertNil(G2OtaWire.reply(raw, expectedCid: 2, expectedSync: v.sync_id + 1))
        XCTAssertNil(G2OtaWire.reply(raw, expectedCid: 3, expectedSync: v.sync_id))
        for i in [1, 3, 4, 5, 6, 7, 8, 9, 10, 11] {
            var changed = raw
            changed[i] ^= 1
            XCTAssertNotEqual(G2OtaWire.reply(changed, expectedCid: 2, expectedSync: v.sync_id), 0, "byte \(i)")
        }
        var rejected = hex(v.start_ack_hex)
        rejected[7] = 2
        XCTAssertEqual(G2OtaWire.reply(rejected, expectedCid: 0, expectedSync: v.sync_id), -1)
    }

    func testImageRequiresMatchingAbsoluteBoundsInnerHeaderAndBodyChecksum() throws {
        let file = fixture()
        let image = try G2OtaWire.parse(file)
        XCTAssertEqual(image.version, "s200_v2.4.1.4")
        XCTAssertEqual(image.components.count, 1)
        XCTAssertEqual(image.components[0].body, Data("123456789".utf8))
        XCTAssertEqual(image.components[0].header, file[96..<224])
        for at in [0, 8, 48, 68, 72, 76, 80, 104, 108, 116, 224] {
            var changed = file
            changed[at] = 0xff
            XCTAssertThrowsError(try G2OtaWire.parse(changed), "offset \(at)")
        }
        XCTAssertThrowsError(try G2OtaWire.parse(file + Data([0])))
        XCTAssertThrowsError(try G2OtaWire.parse(file.dropLast()))
    }

    /// Synthetic S200 image. The fixed CRC of ASCII "123456789" anchors the image checksum dialect.
    private func fixture() -> Data {
        var file = Data(count: 233)
        func put(_ bytes: Data, at: Int) { file.replaceSubrange(at..<at + bytes.count, with: bytes) }
        put(Data("EVENOTA".utf8), at: 0)
        file[8] = 1
        put(Data("s200_v2.4.1.4".utf8), at: 48)
        file[64] = 1
        file[68] = 96
        file[72] = 137
        put(hex("c8a852c0"), at: 76)
        put(Data("evenota".utf8), at: 80)
        file[104] = 9
        put(hex("c8a852c0"), at: 108)
        put(Data("EVEN".utf8), at: 116)
        put(Data("123456789".utf8), at: 224)
        return file
    }
}
