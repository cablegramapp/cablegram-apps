import SwiftUI

/// Port of PhoneComponents.kt: BrandMark, ScreenHeading, SectionCard, StatusNote and the button styles.
struct BrandMark: View {
    var compact = false

    var body: some View {
        HStack(spacing: compact ? 6 : 10) {
            Image("BrandMark").resizable().scaledToFit().frame(height: compact ? 22 : 34)
            Text("Cablegram").font(.system(size: compact ? 15 : 20, weight: .semibold)).foregroundStyle(.white)
        }
        .accessibilityElement(children: .combine)
    }
}

struct ScreenHeading: View {
    let title: String
    var subtitle: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(PhoneFont.headlineLarge).foregroundStyle(.white).frame(maxWidth: .infinity, alignment: .leading)
            if let subtitle, !subtitle.isEmpty {
                Text(subtitle).font(PhoneFont.bodyMedium).foregroundStyle(Color.phoneMuted)
            }
        }
    }
}

struct SectionCard<Content: View>: View {
    let title: String
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title).font(PhoneFont.titleMedium).foregroundStyle(.white)
            VStack(alignment: .leading, spacing: 12) { content }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(18)
                .background(Color.phonePanel, in: RoundedRectangle(cornerRadius: 20))
                .overlay(RoundedRectangle(cornerRadius: 20).stroke(Color.phoneOutline.opacity(0.55), lineWidth: 1))
        }
    }
}

struct StatusNote: View {
    let message: String

    var body: some View {
        Text(message)
            .font(PhoneFont.bodyMedium).foregroundStyle(Color.phoneMuted)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(14)
            .background(Color.phonePanel, in: RoundedRectangle(cornerRadius: PhoneRadius.small))
    }
}

/// Filled accent button (Material `Button`): 54pt tall, pill shaped, greyed when disabled.
struct PrimaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    var minHeight: CGFloat = 54
    var destructive = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(PhoneFont.labelLarge)
            .foregroundStyle(enabled ? Color.black : Color.white.opacity(0.4))
            .frame(maxWidth: .infinity, minHeight: minHeight)
            .background(enabled ? (destructive ? Color.phoneError : Color.phoneAccent) : Color.white.opacity(0.12), in: Capsule())
            .opacity(configuration.isPressed ? 0.85 : 1)
    }
}

/// Outlined button (Material `OutlinedButton`).
struct OutlinedButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    var minHeight: CGFloat = 48
    var color: Color = .phoneAccent
    var borderColor: Color = .phoneOutline

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(PhoneFont.labelLarge)
            .foregroundStyle(enabled ? color : color.opacity(0.4))
            .frame(maxWidth: .infinity, minHeight: minHeight)
            .overlay(Capsule().stroke(borderColor, lineWidth: 1))
            .opacity(configuration.isPressed ? 0.7 : 1)
    }
}

/// A text button in the accent colour (Material `TextButton`).
struct AccentTextButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(PhoneFont.labelLarge)
            .foregroundStyle(enabled ? Color.phoneAccent : Color.phoneAccent.opacity(0.4))
            .padding(.vertical, 8)
            .opacity(configuration.isPressed ? 0.6 : 1)
    }
}

/// Outlined text field with a floating label, like Material `OutlinedTextField`.
struct PhoneTextField: View {
    let label: String
    @Binding var text: String
    var supporting: String?
    var keyboard: UIKeyboardType = .default
    var contentType: UITextContentType?
    var isError = false
    var leftToRight = false
    var onSubmit: (() -> Void)?
    var id: String?
    @FocusState private var focused: Bool
    @Environment(\.layoutDirection) private var layoutDirection

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            ZStack(alignment: .topLeading) {
                RoundedRectangle(cornerRadius: 4)
                    .stroke(isError ? Color.phoneError : (focused ? Color.phoneAccent : Color.phoneOutline), lineWidth: focused ? 2 : 1)
                    .background(Color.phoneBackground)
                VStack(alignment: .leading, spacing: 2) {
                    Text(label).font(.system(size: 12)).foregroundStyle(focused ? Color.phoneAccent : Color.phoneMuted)
                    TextField("", text: $text)
                        .focused($focused)
                        .font(PhoneFont.bodyLarge)
                        .foregroundStyle(.white)
                        .keyboardType(keyboard)
                        .textContentType(contentType)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(keyboard == .emailAddress ? .never : .sentences)
                        .environment(\.layoutDirection, leftToRight ? .leftToRight : layoutDirection)
                        .accessibilityIdentifier(id ?? "")
                        .onSubmit { onSubmit?() }
                        .submitLabel(onSubmit == nil ? .done : .send)
                }
                .padding(.horizontal, 14).padding(.vertical, 8)
            }
            .frame(minHeight: 58)
            if let supporting {
                Text(supporting).font(.system(size: 12)).foregroundStyle(isError ? Color.phoneError : Color.phoneMuted).padding(.horizontal, 14)
            }
        }
    }
}
