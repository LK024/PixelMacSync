import SwiftUI

// Batteria del telefono nello stile dell'indicatore di macOS nella barra dei menu:
// percentuale "ritagliata" dentro il corpo della batteria, riempito in proporzione alla carica.
struct BatteryBadge: View {
    let level: Int?
    let isCharging: Bool

    @Environment(\.colorScheme) private var colorScheme

    // Colore esplicito invece di .primary: nel pannello della barra dei menu i colori di sistema sono "vibranti"
    // (fusi con lo sfondo) e, dentro il livello che ritaglia il numero, risulterebbero spenti
    private var ink: Color {
        colorScheme == .dark ? .white : .black
    }

    private var fraction: CGFloat {
        CGFloat(min(max(level ?? 0, 0), 100)) / 100
    }

    private var fillColor: Color {
        if isCharging { return .green }
        if let level = level, level <= 20 { return .red }
        return ink
    }

    var body: some View {
        HStack(spacing: 1.5) {
            ZStack(alignment: .leading) {
                Rectangle()
                    .fill(ink.opacity(0.3))
                GeometryReader { geometry in
                    Rectangle()
                        .fill(fillColor)
                        .frame(width: geometry.size.width * fraction)
                }
            }
            .frame(width: 28, height: 14)
            .clipShape(RoundedRectangle(cornerRadius: 4.5, style: .continuous))
            // Il numero è un "buco" nel corpo della batteria: si vede lo sfondo del pannello, come in macOS
            .mask {
                ZStack {
                    Rectangle()
                    label.blendMode(.destinationOut)
                }
                .compositingGroup()
            }

            RoundedRectangle(cornerRadius: 1, style: .continuous)
                .fill(ink.opacity(0.4))
                .frame(width: 2, height: 5)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Batteria telefono \(level.map { "\($0)%" } ?? "sconosciuta")\(isCharging ? ", in carica" : "")")
    }

    private var label: some View {
        HStack(spacing: 0.5) {
            Text(level.map(String.init) ?? "–")
                .font(.system(size: 10, weight: .bold))
                .monospacedDigit()
            if isCharging {
                Image(systemName: "bolt.fill")
                    .font(.system(size: 7, weight: .bold))
            }
        }
    }
}
