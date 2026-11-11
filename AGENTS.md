# Enterprise native SDK

G2-only native Kotlin/Swift SDK; no Flutter runtime. Preserve native license ownership, exact-SN fail-closed admission, dual-leg enterprise/business AUTH and generation-fenced callbacks. Enterprise connections use 8450/8401/8402/8403 and never fall back to legacy services. R1 is outside scope.

Keep public API and Maven coordinates/SPM product stable during repository migration. Published beta.14 binaries and prior repository tags are immutable. Do not include real licenses, signing credentials, personal/device logs or local machine paths. Generated codec outputs and third-party binaries retain audited provenance and licenses.

For code changes, explain ownership and non-obvious ordering in comments. Use Lore-style commit messages with intent plus relevant Constraint/Tested/Not-tested trailers. Do not overwrite changes owned by another contributor. Verify native tests, build artifacts, pure-native examples and Flutter consumers before claiming completion; compilation is not physical-device acceptance.
