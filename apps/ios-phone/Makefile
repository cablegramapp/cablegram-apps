# Cablegram for iPhone. `make project` generates the Xcode project (XcodeGen); it is never committed.
DEST ?= platform=iOS Simulator,name=iPhone 17
XCB = xcodebuild -project Cablegram.xcodeproj -scheme Cablegram -destination '$(DEST)' -derivedDataPath build

.PHONY: legal project build test core-test ui-test strings maestro-ids

legal:
	mkdir -p Cablegram/Resources/legal && cp ../../LICENSE ../../NOTICE Cablegram/Resources/legal/

project: legal
	xcodegen generate

strings:
	python3 ../../scripts/localization/android-to-xcstrings.py

maestro-ids:
	python3 scripts/gen-maestro-ids.py

core-test:
	cd Packages/CablegramCore && swift test

build: project
	$(XCB) build

test: core-test project
	$(XCB) test

ui-test:
	@echo "UI flows live in quality/maestro/phone; run them with maestro-runner against the booted simulator."
