import XCTest

final class TransformControlsUITests: XCTestCase {
    @MainActor
    func testNativeDescenderPreviewIsVisibleOnScreen() {
        let app = XCUIApplication()
        app.launchArguments += ["-spotled.text", "gUq"]
        app.launch()

        let textField = app.textFields["text.message"]
        XCTAssertTrue(textField.waitForExistence(timeout: 15))
        XCTAssertEqual(textField.value as? String, "gUq")

        let previewLabel = app.staticTexts["64×16 preview"]
        for _ in 0..<8 where !previewLabel.isHittable { app.swipeUp() }
        XCTAssertTrue(previewLabel.waitForExistence(timeout: 10))
        XCTAssertTrue(previewLabel.isHittable)
        XCTAssertTrue(app.images["text.preview"].isHittable)

        let screenshot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        screenshot.name = "iPhone native text preview — gUq descenders"
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }

    @MainActor
    func testImageRotationDoesNotChangeTextAndTextOffersPortraitModes() {
        let app = XCUIApplication()
        app.launchArguments += ["-spotled.text", "HELLO"]
        app.launch()

        let rendering = app.descendants(matching: .any)
            .matching(identifier: "text.rendering").firstMatch
        let textOrientation = app.descendants(matching: .any)
            .matching(identifier: "text.orientation").firstMatch
        XCTAssertTrue(rendering.waitForExistence(timeout: 15))
        XCTAssertTrue(textOrientation.exists)

        app.textFields["text.message"].tap()
        let keyboardDone = app.buttons["Done"].firstMatch
        XCTAssertTrue(keyboardDone.waitForExistence(timeout: 5))
        keyboardDone.tap()

        rendering.tap()
        app.buttons["Normal"].tap()
        let byteSummary = app.staticTexts.matching(identifier: "text.transferLimit").firstMatch
        XCTAssertTrue(byteSummary.waitForExistence(timeout: 5))
        XCTAssertTrue(byteSummary.label.contains("WordStock 259 B"))
        XCTAssertTrue(byteSummary.label.contains("CharacterString 138 B"))

        textOrientation.tap()
        app.buttons["Portrait"].tap()
        XCTAssertTrue(app.staticTexts["Rotated native glyphs preserve the full message and support Scroll Left."]
            .waitForExistence(timeout: 5))
        XCTAssertTrue(byteSummary.label.contains("WordStock 259 B"))
        XCTAssertTrue(byteSummary.label.contains("CharacterString 138 B"))
        textOrientation.tap()
        app.buttons["Landscape"].tap()
        XCTAssertTrue(byteSummary.label.contains("WordStock 259 B"))

        let imageRotation = app.descendants(matching: .any)
            .matching(identifier: "image.rotation").firstMatch
        let imageFit = app.descendants(matching: .any)
            .matching(identifier: "image.fit").firstMatch
        for _ in 0..<4 where !imageRotation.exists { app.swipeUp() }
        XCTAssertTrue(imageRotation.waitForExistence(timeout: 5))
        XCTAssertTrue(imageFit.exists)
        imageRotation.tap()
        app.buttons["90°"].tap()

        for _ in 0..<4 where !byteSummary.exists { app.swipeDown() }
        XCTAssertTrue(byteSummary.label.contains("WordStock 259 B"))
        XCTAssertTrue(byteSummary.label.contains("CharacterString 138 B"))
    }
}
