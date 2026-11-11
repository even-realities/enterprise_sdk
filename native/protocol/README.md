# G2 native protocol baseline

`contract.json` is the shared Android/iOS migration contract. It includes
enterprise authorization, G2 business service IDs, and exact wire vectors.
The standalone native SDK only admits device settings, notifications, and
EvenHub. The existing Flutter package still has its own R1 implementation;
that implementation is outside this native release. Any new G2 SID needs a
product-scope review and a protocol-boundary test before native decoding.

`test/native_protocol_contract_test.dart` checks G2 values and vectors against
the current Dart implementation. `tool/check_native_protocol_sources.sh`
compiles the ten G2 protobuf roots separately. Their upstream files have no
protobuf package, so compiling unrelated roots together would collide on
global names. This check does not generate or edit native platform code.

The 8403 payload vector reflects the **provisional** encoder. Firmware has not
finalized the wire format. A passing vector test does not prove that real G2
devices accept the payload. Freeze the final format and verify both legs before
releasing a native SDK.

OTA and EFS have separate transport channels; the business SID list does not
authorize those channels. Each platform must reject unknown business SIDs
before protobuf default values can interpret them. A successful 8403 result
must be followed by business AUTH on both legs before whole-glasses READY.

The framed AUTH vectors include the received CRC trailer. Native decoders must
compare it with a fresh CRC calculation. The migration test does this
explicitly because Dart `EvenBleTransport.fromBytes` currently reconstructs
`crc` from payload rather than retaining the received trailer.

TimeSync vectors use a negative half-hour offset to catch signed encoding
errors. Dart sends the command with `syncBoth=true` (header flag `0x20`),
even when it names the current right endpoint. A successful business reply
has `notify=false`; its transport sync can differ from the request, so match
the current endpoint, service and protobuf magic while rejecting old attempts.

[g2_ota_vectors.json](g2_ota_vectors.json) contains synthetic single-endpoint
G2 OTA frames, checked against the legacy encoder by
`test/native_g2_ota_vectors_test.dart`. The OTA channel uses service 1001,
write 0001 and notify 0002; C0 carries control and C1 carries raw windows.
The fixture includes a full-size fragment whose CRC must be sent in a separate
final frame. FILE admission and a successful BLE write are not a window ACK.
A final installation notification also does not contain the installed versions.

For post-reboot OTA verification, the SDK issues one private BASIC_SETTING
request (SID 09, command 2) on the authenticated business link. The
[readback vectors](g2_ota_version_vectors.json) include the largest response
under the current schema. This operation reads both versions from a single
reply; it does not add SID 09 to the general business API.
