import SwiftUI
import UIKit
import ComposeApp

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            // Full-bleed: Compose reads the safe area itself (status bar, home indicator, keyboard),
            // so SwiftUI must not inset the view or the window background shows through at the edges.
            .ignoresSafeArea(.all)
    }
}
