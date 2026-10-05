import SwiftUI

/// 10pt uppercase label used above inspector groups and tile captions.
struct Kicker: View {
    let text: String

    init(_ text: String) {
        self.text = text
    }

    var body: some View {
        Text(text.uppercased())
            .font(.nocturne(10))
            .tracking(1.2)
            .foregroundStyle(Nocturne.neutral600)
    }
}

struct StatusDot: View {
    enum Tone {
        case on, warning, idle, off
    }

    var tone: Tone = .on

    var body: some View {
        Circle()
            .fill(color)
            .frame(width: 6, height: 6)
            .shadow(color: tone == .on ? Nocturne.accent : .clear, radius: 4)
    }

    private var color: Color {
        switch tone {
        case .on: Nocturne.accent
        case .warning: Nocturne.accent300
        case .idle: Nocturne.neutral400
        case .off: Nocturne.neutral700
        }
    }
}

/// Horizontal rule that fades to transparent at both ends.
struct FadedRule: View {
    var color: Color = Nocturne.text.opacity(0.08)

    var body: some View {
        LinearGradient(
            stops: [
                .init(color: .clear, location: 0),
                .init(color: color, location: 0.11),
                .init(color: color, location: 0.89),
                .init(color: .clear, location: 1),
            ],
            startPoint: .leading,
            endPoint: .trailing
        )
        .frame(height: 1)
    }
}

/// "Sense" tile: surface panel with an uppercase caption row and a right-aligned mono value.
struct Tile<Content: View>: View {
    let caption: String
    var value: String?
    var isFocused = false
    @ViewBuilder var content: Content

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: Nocturne.Radius.md)
        VStack(alignment: .leading, spacing: 0) {
            TileCaption(caption: caption, value: value)
            content
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(Nocturne.surface)
        .clipShape(shape)
        .overlay(shape.strokeBorder(isFocused ? Nocturne.accent600 : Nocturne.neutral900, lineWidth: 1))
    }
}

struct TileCaption: View {
    let caption: String
    var value: String?

    var body: some View {
        HStack(spacing: 8) {
            Kicker(caption)
            Spacer(minLength: 0)
            if let value {
                Text(value)
                    .font(.nocturneMono(11))
                    .foregroundStyle(Nocturne.neutral500)
                    .lineLimit(1)
            }
        }
        .padding(EdgeInsets(top: 9, leading: 12, bottom: 0, trailing: 12))
    }
}

/// Elevated card with a hairline edge.
struct Card<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: Nocturne.Radius.md)
        VStack(alignment: .leading, spacing: 8) {
            content
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Nocturne.surface, in: shape)
        .overlay(shape.strokeBorder(Nocturne.neutral800, lineWidth: 1))
    }
}

/// The robot's face: two glowing eyes that blink every ~6 s.
struct RobotFace: View {
    enum Size {
        case small, large

        var side: CGFloat { self == .small ? 28 : 60 }
        var radius: CGFloat { self == .small ? 7 : 12 }
        var eyeRadius: CGFloat { self == .small ? 3 : 5 }
        var gap: CGFloat { self == .small ? 3 : 6 }
        var glow: CGFloat { self == .small ? 3 : 4 }
        var insets: EdgeInsets {
            self == .small
                ? EdgeInsets(top: 8, leading: 6, bottom: 10, trailing: 6)
                : EdgeInsets(top: 16, leading: 12, bottom: 20, trailing: 12)
        }
    }

    var size: Size = .small

    @State private var isBlinking = false

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: size.radius)
        HStack(spacing: size.gap) {
            eye
            eye
        }
        .padding(size.insets)
        .frame(width: size.side, height: size.side)
        .background(Nocturne.faceGround, in: shape)
        .overlay(shape.strokeBorder(Nocturne.neutral800, lineWidth: 1))
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(5.8))
                withAnimation(.easeInOut(duration: 0.09)) { isBlinking = true }
                try? await Task.sleep(for: .milliseconds(90))
                withAnimation(.easeInOut(duration: 0.09)) { isBlinking = false }
            }
        }
    }

    private var eye: some View {
        RoundedRectangle(cornerRadius: size.eyeRadius)
            .fill(Nocturne.eye)
            .shadow(color: Nocturne.eye.opacity(0.9), radius: size.glow)
            .scaleEffect(y: isBlinking ? 0.1 : 1)
    }
}

/// Mono "label value" pair where the value is brighter than the label.
struct LabeledValue: View {
    let label: String
    let value: String
    var unit: String?

    var body: some View {
        HStack(spacing: 0) {
            Text(label + " ")
            Text(value).foregroundStyle(Nocturne.neutral100)
            if let unit {
                Text(" " + unit)
            }
        }
        .lineLimit(1)
    }
}
