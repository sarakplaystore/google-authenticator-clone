package app.openauth.authenticator;

/**
 * In-memory session state for the face gate. Nothing here is persisted; after
 * the process dies the app starts locked and requires face recognition again.
 */
final class FaceGate {
  static boolean unlocked;
  /** True while an internal activity (scanner or face registration) is open. */
  static boolean internalNavigation;
  /** Set when another screen returns after the app was backgrounded. */
  static boolean promptOnNextResume;

  private FaceGate() {}
}
