import UIKit

/// A native UIKit host: the SDK remains the only Bluetooth/protocol owner.
@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.rootViewController = UINavigationController(rootViewController: GlassesViewController())
        window.makeKeyAndVisible()
        self.window = window
        return true
    }
}
