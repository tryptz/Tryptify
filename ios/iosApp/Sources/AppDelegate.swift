import UIKit
import MonochromeKit

/**
 * Classic (pre-scene) UIKit lifecycle — smallest viable shell for a full-screen
 * Compose Multiplatform app. The Compose root comes from the statically linked
 * MonochromeKit framework (`MainKt.MainViewController`).
 */
class AppDelegate: NSObject, UIApplicationDelegate {
    var window: UIWindow?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.rootViewController = MainKt.MainViewController()
        window.makeKeyAndVisible()
        self.window = window
        return true
    }
}
