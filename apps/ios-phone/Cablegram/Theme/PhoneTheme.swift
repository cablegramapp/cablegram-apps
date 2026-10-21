import SwiftUI

/// Port of PhoneTheme.kt: same palette, type scale and corner radii. Names follow the Android tokens.
extension Color {
    init(hex: UInt32) {
        self.init(.sRGB, red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255, opacity: 1)
    }

    static let phoneAccent = Color(hex: 0xF4BD75)        // VlcOrange / primary
    static let phoneBackground = Color(hex: 0x101114)    // VlcBlack
    static let phonePanel = Color(hex: 0x1B1D22)         // VlcPanel / surface
    static let phonePanelHigh = Color(hex: 0x25272E)     // surfaceContainerHigh
    static let phoneMuted = Color(hex: 0xAAADB7)         // VlcMuted
    static let phoneOutline = Color(hex: 0x34363E)
    static let phoneSuccess = Color(hex: 0xA5CBB1)
    static let phoneSuccessContainer = Color(hex: 0x28372F)
    static let phoneAccentContainer = Color(hex: 0x3D3022)
    static let phoneError = Color(hex: 0xFFB4AB)
}

enum PhoneRadius {
    static let small: CGFloat = 12
    static let medium: CGFloat = 18
    static let large: CGFloat = 24
}

/// Material typography mapped to fixed point sizes that still scale with Dynamic Type.
/// Text styles, so the app follows Dynamic Type (Android follows the font scale). The sizes are the platform's defaults for each style.
enum PhoneFont {
    static let headlineLarge = Font.system(.largeTitle).weight(.semibold)
    static let headlineMedium = Font.system(.title).weight(.semibold)
    static let headlineSmall = Font.system(.title).weight(.semibold)
    static let titleLarge = Font.system(.title2).weight(.semibold)
    static let titleMedium = Font.system(.headline)
    static let bodyLarge = Font.system(.callout)
    static let bodyMedium = Font.system(.subheadline)
    static let labelLarge = Font.system(.subheadline).weight(.medium)
    static let labelMedium = Font.system(.caption).weight(.medium)
}

extension View {
    func phoneScreenBackground() -> some View {
        background(Color.phoneBackground.ignoresSafeArea())
    }
}
