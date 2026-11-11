import Foundation
import XCTest
@testable import EvenDevicesSDK

/// Regresses the ANCS/system-connected right temple that no longer advertises.
final class G2EndpointPairTests: XCTestCase {
    private let sn = "S200TEST000001"
    private let left = UUID(uuidString: "00000000-0000-4000-8000-000000000001")!
    private let right = UUID(uuidString: "00000000-0000-4000-8000-000000000002")!

    func testKnownPairRemainsResolvableWhenOnlyLeftAdvertises() throws {
        let known = try XCTUnwrap(G2EndpointPair(left: left, right: right))
        XCTAssertEqual(G2EndpointPair.select(serialNumber: sn,
            advertised: [sn: [.left: left]], preferred: known), known)
        XCTAssertNil(G2EndpointPair.select(serialNumber: sn,
            advertised: [sn: [.left: left]], preferred: nil))
    }

    func testFreshDiscoveryRequiresTwoDistinctTemplesForTheSameSerial() {
        XCTAssertEqual(G2EndpointPair.select(serialNumber: sn,
            advertised: [sn: [.left: left, .right: right]], preferred: nil),
            G2EndpointPair(left: left, right: right))
        XCTAssertNil(G2EndpointPair(left: left, right: left))
        XCTAssertNil(G2EndpointPair.select(serialNumber: sn,
            advertised: [sn: [.left: left], "S211GABE130301": [.right: right]], preferred: nil))
    }

    func testEndpointHintsCannotOverrideContradictoryAdvertisedIdentity() throws {
        let known = try XCTUnwrap(G2EndpointPair(left: left, right: right))
        XCTAssertNil(G2EndpointPair.select(serialNumber: sn,
            advertised: ["S211GABE130301": [.right: right]], preferred: known))
        XCTAssertNil(G2EndpointPair.select(serialNumber: sn,
            advertised: [sn: [.left: right]], preferred: known))
    }
}
