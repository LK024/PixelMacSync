import SwiftUI

struct ContentView: View {
    @ObservedObject var bleManager: BLEManager
    
    // Funzione di supporto corretta per l'icona della batteria
    var batteryIconName: String {
        if bleManager.isCharging {
            return "battery.100.bolt"
        }
       
        let level = Int(bleManager.batteryLevel.replacingOccurrences(of: "%", with: "")) ?? 50
        if level <= 12 { return "battery.0" }
        if level <= 37 { return "battery.25" }
        if level <= 62 { return "battery.50" }
        if level <= 87 { return "battery.75" }
        return "battery.100"
    }
    
    var body: some View {
        VStack(spacing: 16) {
            
            // --- SEZIONE 1: STATO CONNESSIONE E DISPOSITIVO ---
            HStack {
                if bleManager.isSwitchedOn && bleManager.connectionStatus.contains("Connesso") {
                    // ICONA TELEFONO: il clic apre le opzioni del telefono associato
                    if bleManager.hasTrustedPhone {
                        Menu {
                            forgetPhoneButton
                        } label: {
                            Image(systemName: "iphone")
                                .foregroundColor(.primary)
                        }
                        .menuStyle(.borderlessButton)
                        .menuIndicator(.hidden)
                        .fixedSize()
                        .help("Opzioni telefono associato")
                    } else {
                        Image(systemName: "iphone")
                            .foregroundColor(.primary)
                    }
                } else if bleManager.isSwitchedOn {
                    // ICONA ANTENNA: il clic apre riavvio ricerca e, se c'è, "dimentica telefono"
                    Menu {
                        Button("Riavvia ricerca") {
                            bleManager.forceRestartBluetooth()
                        }
                        if bleManager.hasTrustedPhone {
                            Divider()
                            forgetPhoneButton
                        }
                    } label: {
                        Image(systemName: "antenna.radiowaves.left.and.right")
                            .foregroundColor(.accentColor)
                    }
                    .menuStyle(.borderlessButton)
                    .menuIndicator(.hidden)
                    .fixedSize()
                    .help("Opzioni ricerca Bluetooth")
                } else {
                    Image(systemName: "antenna.radiowaves.left.and.right.slash")
                        .foregroundColor(.red)
                        .help("Bluetooth spento")
                }
                
                Text(bleManager.connectionStatus.contains("Connesso") ? bleManager.deviceName : bleManager.connectionStatus)
                    .font(.headline)
                    .fixedSize(horizontal: true, vertical: false)
                
                Spacer()
                
                if bleManager.connectionStatus.contains("Connesso") {

                    // Segnale di Rete
                    HStack(spacing: 4) {
                        Text(bleManager.networkType).font(.system(size: 10, weight: .bold)).foregroundColor(.secondary).lineLimit(1).minimumScaleFactor(0.6)
                        
                        if bleManager.isWifi {
                            Image(systemName: "wifi")
                                .foregroundColor(.primary)
                        }
                        
                        Image(systemName: "cellularbars", variableValue: Double(bleManager.signalStrength) / 4.0)
                            .foregroundColor(.primary)
                    }
                    
                    // Batteria
                    HStack(spacing: 4) {
                        Text(bleManager.batteryLevel)
                            .font(.caption)
                            .monospacedDigit().fixedSize(horizontal: true, vertical: false)
                        
                        Image(systemName: batteryIconName)
                            .symbolRenderingMode(.hierarchical)
                            .foregroundColor(bleManager.isCharging ? .green : (levelColor(for: bleManager.batteryLevel)))
                    }
                    .padding(.leading, 4)
                }
            }
            
            Divider()
            
            // --- SEZIONE 2: HOTSPOT ---
            // Il comando parte solo da un clic dell'utente: gli aggiornamenti di telemetria cambiano il toggle senza rimandare nulla al telefono
            Toggle(isOn: Binding(
                get: { bleManager.isHotspotActive },
                set: { bleManager.setRemoteHotspot(enable: $0) }
            )) {
                HStack {
                    Image(systemName: "personalhotspot")
                        .foregroundColor(bleManager.isHotspotActive ? .accentColor : .secondary)
                    Text("Hotspot Remoto")
                        .font(.subheadline)
                }
            }
            .toggleStyle(.switch)

        }
        .padding(16)
        .frame(width: 320)
    }
    
    // Dopo averlo dimenticato, il prossimo telefono abbinato diventerà quello fidato
    private var forgetPhoneButton: some View {
        Button("Dimentica telefono associato") {
            bleManager.forgetTrustedPhone()
        }
    }

    func levelColor(for levelStr: String) -> Color {
        let level = Int(levelStr.replacingOccurrences(of: "%", with: "")) ?? 50
        if level <= 20 { return .red }
        return .primary
    }
}
