import SwiftUI

// MARK: - Buttons

/// Outlined Nocturne buttons: primary = accent outline, secondary = divider outline, ghost = text only.
struct NocturneButtonStyle: ButtonStyle {
    enum Kind {
        case primary, secondary, ghost
    }

    var kind: Kind = .secondary
    var fontSize: CGFloat = 12
    var foreground: Color?

    func makeBody(configuration: Configuration) -> some View {
        NocturneButtonChrome(style: self, configuration: configuration)
    }
}

extension ButtonStyle where Self == NocturneButtonStyle {
    static var nocturnePrimary: NocturneButtonStyle { NocturneButtonStyle(kind: .primary) }
    static var nocturneSecondary: NocturneButtonStyle { NocturneButtonStyle(kind: .secondary) }
    static var nocturneGhost: NocturneButtonStyle { NocturneButtonStyle(kind: .ghost) }
}

private struct NocturneButtonChrome: View {
    let style: NocturneButtonStyle
    let configuration: ButtonStyleConfiguration

    @Environment(\.isEnabled) private var isEnabled
    @State private var isHovering = false

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: Nocturne.Radius.md)
        let isGhost = style.kind == .ghost
        HStack(spacing: 6) {
            configuration.label
        }
        .font(.nocturne(style.fontSize, .medium))
        .foregroundStyle(style.foreground ?? foreground)
        .lineLimit(1)
        .padding(.horizontal, isGhost ? 3 : 10)
        .padding(.vertical, isGhost ? 5 : 4)
        .background(fill, in: shape)
        .overlay(shape.strokeBorder(border, lineWidth: 1))
        .contentShape(shape)
        .opacity(isEnabled ? 1 : 0.45)
        .onHover { isHovering = $0 }
    }

    private var foreground: Color {
        style.kind == .secondary ? Nocturne.text : Nocturne.accent
    }

    private var border: Color {
        switch style.kind {
        case .primary: Nocturne.accent
        case .secondary: Nocturne.divider
        case .ghost: .clear
        }
    }

    private var fill: Color {
        guard isEnabled else { return .clear }
        let base = style.kind == .secondary ? Nocturne.text : Nocturne.accent
        let (hover, pressed): (Double, Double) = switch style.kind {
        case .primary: (0.12, 0.22)
        case .secondary: (0.07, 0.14)
        case .ghost: (0.10, 0.18)
        }
        if configuration.isPressed { return base.opacity(pressed) }
        return isHovering ? base.opacity(hover) : .clear
    }
}

// MARK: - Segmented control

struct NocturneSegmented<Value: Hashable>: View {
    @Binding var selection: Value
    let options: [(value: Value, label: String)]
    var fontSize: CGFloat = 12
    var horizontalPadding: CGFloat = 9
    var verticalPadding: CGFloat = 4

    var body: some View {
        HStack(spacing: 0) {
            ForEach(options.indices, id: \.self) { index in
                let option = options[index]
                if index > 0 {
                    Rectangle().fill(Nocturne.divider).frame(width: 1)
                }
                Segment(
                    label: option.label,
                    isSelected: option.value == selection,
                    fontSize: fontSize,
                    padding: EdgeInsets(
                        top: verticalPadding, leading: horizontalPadding,
                        bottom: verticalPadding, trailing: horizontalPadding
                    )
                ) {
                    selection = option.value
                }
            }
        }
        .fixedSize()
        .clipShape(RoundedRectangle(cornerRadius: Nocturne.Radius.md))
        .overlay(RoundedRectangle(cornerRadius: Nocturne.Radius.md).strokeBorder(Nocturne.divider, lineWidth: 1))
    }

    private struct Segment: View {
        let label: String
        let isSelected: Bool
        let fontSize: CGFloat
        let padding: EdgeInsets
        let action: () -> Void

        @State private var isHovering = false

        var body: some View {
            Button(action: action) {
                Text(label)
                    .font(.nocturne(fontSize))
                    .foregroundStyle(isSelected ? Nocturne.accent : Nocturne.text)
                    .padding(padding)
                    .background(isHovering && !isSelected ? Nocturne.text.opacity(0.07) : .clear)
                    .overlay(Rectangle().strokeBorder(isSelected ? Nocturne.accent : .clear, lineWidth: 1))
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .onHover { isHovering = $0 }
        }
    }
}

// MARK: - Checkbox

struct NocturneCheckboxStyle: ToggleStyle {
    func makeBody(configuration: Configuration) -> some View {
        Button {
            configuration.isOn.toggle()
        } label: {
            HStack(spacing: 8) {
                RoundedRectangle(cornerRadius: 3)
                    .fill(configuration.isOn ? Nocturne.accent : .clear)
                    .padding(3.5)
                    .frame(width: 16, height: 16)
                    .overlay(
                        RoundedRectangle(cornerRadius: 3)
                            .strokeBorder(configuration.isOn ? Nocturne.accent : Nocturne.divider, lineWidth: 1.5)
                    )
                configuration.label
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Stepper

/// Bordered `−` / value field / `+` stepper used for leg offsets.
struct OffsetStepper: View {
    @Binding var value: Double
    var step = 0.5
    var range: ClosedRange<Double> = -15...15

    @State private var text = ""
    @FocusState private var isFocused: Bool

    var body: some View {
        HStack(spacing: 0) {
            StepButton(symbol: "−") { nudge(-step) }
            Rectangle().fill(Nocturne.divider).frame(width: 1)
            TextField("", text: $text)
                .textFieldStyle(.plain)
                .multilineTextAlignment(.center)
                .font(.nocturneMono(12, .medium))
                .foregroundStyle(Nocturne.text)
                .tint(Nocturne.accent)
                .frame(width: 56)
                .padding(.vertical, 4)
                .background(Nocturne.bg)
                .focused($isFocused)
                .onSubmit(commit)
            Rectangle().fill(Nocturne.divider).frame(width: 1)
            StepButton(symbol: "+") { nudge(step) }
        }
        .fixedSize()
        .clipShape(RoundedRectangle(cornerRadius: Nocturne.Radius.md))
        .overlay(RoundedRectangle(cornerRadius: Nocturne.Radius.md).strokeBorder(Nocturne.divider, lineWidth: 1))
        .onAppear { text = Self.format(value) }
        .onChange(of: value) { text = Self.format(value) }
        .onChange(of: isFocused) { if !isFocused { commit() } }
    }

    private func nudge(_ delta: Double) {
        value = min(max(value + delta, range.lowerBound), range.upperBound)
    }

    private func commit() {
        let cleaned = text.replacingOccurrences(of: "−", with: "-").replacingOccurrences(of: "+", with: "")
        if let parsed = Double(cleaned.trimmingCharacters(in: .whitespaces)) {
            value = min(max(parsed, range.lowerBound), range.upperBound)
        }
        text = Self.format(value)
    }

    private static func format(_ value: Double) -> String {
        String(format: value > 0 ? "+%.1f" : "%.1f", value)
    }

    private struct StepButton: View {
        let symbol: String
        let action: () -> Void

        @State private var isHovering = false

        var body: some View {
            Button(action: action) {
                Text(symbol)
                    .font(.nocturneMono(13, .medium))
                    .foregroundStyle(isHovering ? Nocturne.accent200 : Nocturne.neutral300)
                    .padding(.horizontal, 10)
                    .frame(maxHeight: .infinity)
                    .background(isHovering ? Nocturne.accent.opacity(0.12) : .clear)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .onHover { isHovering = $0 }
        }
    }
}
