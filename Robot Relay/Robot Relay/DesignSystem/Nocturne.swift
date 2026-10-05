import SwiftUI

/// Nocturne design tokens. Values mirror the design handoff's `styles.css`.
enum Nocturne {
    static let bg = Color(hex: 0x161826)
    static let surface = Color(hex: 0x232532)
    static let text = Color(hex: 0xe9e9ed)
    static let accent = Color(hex: 0x9184d9)
    static let divider = text.opacity(0.16)

    static let neutral100 = Color(hex: 0xf3f5fe)
    static let neutral200 = Color(hex: 0xe4e7f5)
    static let neutral300 = Color(hex: 0xcfd3e5)
    static let neutral400 = Color(hex: 0xb2b6ca)
    static let neutral500 = Color(hex: 0x9397ab)
    static let neutral600 = Color(hex: 0x75798c)
    static let neutral700 = Color(hex: 0x595d6c)
    static let neutral800 = Color(hex: 0x3f424d)
    static let neutral900 = Color(hex: 0x292b31)

    static let accent100 = Color(hex: 0xf5f4ff)
    static let accent200 = Color(hex: 0xe7e5fe)
    static let accent300 = Color(hex: 0xd2cefd)
    static let accent400 = Color(hex: 0xb5abfc)
    static let accent500 = Color(hex: 0x968ae0)
    static let accent600 = Color(hex: 0x796cbf)
    static let accent700 = Color(hex: 0x5d5294)
    static let accent800 = Color(hex: 0x423a6a)
    static let accent900 = Color(hex: 0x2b2741)

    /// Robot face colors.
    static let eye = Color(hex: 0x3ccfff)
    static let faceGround = Color(hex: 0x0d0712)

    enum Radius {
        static let sm: CGFloat = 4
        static let md: CGFloat = 8
        static let lg: CGFloat = 14
    }
}

extension Color {
    init(hex: UInt32) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xff) / 255,
            green: Double((hex >> 8) & 0xff) / 255,
            blue: Double(hex & 0xff) / 255
        )
    }
}

extension Font {
    /// Body face. The design specifies Inter; the system face stands in until it is bundled.
    static func nocturne(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font {
        .system(size: size, weight: weight)
    }

    static func nocturneMono(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font {
        .system(size: size, weight: weight, design: .monospaced)
    }
}
