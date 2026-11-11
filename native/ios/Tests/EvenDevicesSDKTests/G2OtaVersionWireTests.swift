import XCTest
@testable import EvenDevicesSDK

final class G2OtaVersionWireTests: XCTestCase {
    private func vector(_ name: String) throws -> Data {
        let protocolDir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("protocol")
        let file = try Data(contentsOf: protocolDir.appendingPathComponent("g2_ota_version_vectors.json"))
        let hex = try XCTUnwrap(JSONDecoder().decode([String: String].self, from: file)[name])
        return Data(stride(from: 0, to: hex.count, by: 2).map { index in
            UInt8(hex.dropFirst(index).prefix(2), radix: 16)!
        })
    }

    func testPairReadbackValidatesMagicFlagsAndCRC() throws {
        XCTAssertEqual(G2OtaVersionWire.request(syncID: 23, magic: 169), try vector("request"))
        let frame = try vector("response")
        let versions = try XCTUnwrap(G2OtaVersionWire.response(frame + Data([0]), magic: 169))
        XCTAssertEqual(versions.left, "2.4.1.2")
        XCTAssertEqual(versions.right, "2.4.1.2")
        XCTAssertTrue(versions.matches(target: "V2.4.1.2.0"))
        XCTAssertFalse(versions.matches(target: "2.4.1.3"))
        XCTAssertFalse(G2OtaVersions(left: versions.left, right: "2.4.1").matches(target: versions.left))
        // All 19 current schema fields at maximum encoded size still fit MTU 150.
        let allFields = try vector("responseAllFields")
        XCTAssertEqual(allFields.count, 146)
        XCTAssertTrue(try XCTUnwrap(G2OtaVersionWire.response(allFields, magic: 169)).matches(target: "2.4.123.456"))
        XCTAssertNil(G2OtaVersionWire.response(frame, magic: 170))
        XCTAssertNil(G2OtaVersionWire.response(frame.dropLast(), magic: 169))
        let changes: [(Int, UInt8)] = [(0, 0), (1, 0x21), (4, 2), (5, 2), (6, 128), (7, 1), (7, 2), (8, 0)]
        for (index, value) in changes {
            var changed = frame
            changed[index] = value
            XCTAssertNil(G2OtaVersionWire.response(changed, magic: 169))
        }
        XCTAssertNil(G2BusinessService(rawValue: 9))
    }

    func testSelectorMissingLegAndAmbiguousVersionsFailClosed() throws {
        let frame = try vector("response")
        // Keep CRC valid so each rejection exercises the protobuf and version checks.
        let changes: [(Int, UInt8)] = [(9, 1), (13, 26), (16, 0), (17, 50), (26, 58), (19, 0xff)]
        for (index, value) in changes {
            var changed = frame
            changed[index] = value
            let crc = G2AuthenticationWire.crc16(Array(changed[8..<(changed.count - 2)]))
            changed[changed.count - 2] = UInt8(crc & 255)
            changed[changed.count - 1] = UInt8(crc >> 8)
            XCTAssertNil(G2OtaVersionWire.response(changed, magic: 169))
        }
        for invalid in ["", "V", "vV2", "0.0.0", "2..4", "2.bad", "2.4-beta", "２.４", "2.4."] {
            XCTAssertFalse(G2OtaVersions.isValidTarget(invalid))
        }
    }
}
